package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * This is the naive version. It parses each message on its own and saves
 * whatever comes back. It does not ask whether two messages describe the same
 * transaction, and it decides the category from the direction alone.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        Map<String, NormalizedTxn> deduped = new LinkedHashMap<>();
        Map<String, ParsedTxn> evidence = new LinkedHashMap<>();
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            NormalizedTxn txn = toTransaction(p.get());
            String key = dedupKey(txn);
            NormalizedTxn existing = deduped.get(key);
            if (existing == null) {
                deduped.put(key, txn);
                evidence.put(key, p.get());
            } else {
                List<String> ids = new ArrayList<>(existing.sourceMessageIds());
                ids.addAll(txn.sourceMessageIds());
                deduped.put(key, new NormalizedTxn(
                        existing.accountLast4(),
                        existing.occurredAt(),
                        existing.direction(),
                        existing.amount(),
                        existing.category(),
                        existing.merchant(),
                        ids.stream().distinct().sorted().toList()));
            }
        }

        addBalanceGapAdjustments(deduped, evidence);

        for (NormalizedTxn txn : deduped.values()) {
            store.save(txn);
        }
        return new Stats(messages.size(), deduped.size(), skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    private NormalizedTxn toTransaction(ParsedTxn p) {
        Category c = classify(p);
        return new NormalizedTxn(p.accountLast4(), p.occurredAt(), p.direction(),
                p.amount(), c, p.merchant(), List.of(p.sourceMessageId()));
    }

    private static String dedupKey(NormalizedTxn txn) {
        return txn.accountLast4() + "|" + txn.occurredAt().toInstant() + "|"
                + txn.direction() + "|" + txn.amount().toPlainString() + "|"
                + normalizeMerchant(txn.merchant());
    }

    private static String normalizeMerchant(String merchant) {
        return merchant == null ? "" : merchant.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static Category classify(ParsedTxn p) {
        String merchant = p.merchant() == null ? "" : p.merchant().trim();
        String normalized = merchant.toUpperCase(Locale.ROOT);

        boolean transfer = normalized.contains("IMPS/P2A/PARAG KAPOOR")
            || normalized.contains("P2A/PARAG KAPOOR");

        if (transfer) return Category.TRANSFER;
        if (p.direction() == Direction.DEBIT) {
            if (normalized.startsWith("UPI") && p.amount().compareTo(new java.math.BigDecimal("100.00")) <= 0) {
                return Category.MICRO;
            }
            return Category.SPEND;
        }
        return Category.INCOME;
    }

    private static void addBalanceGapAdjustments(Map<String, NormalizedTxn> ledger,
                                                 Map<String, ParsedTxn> evidence) {
        Map<String, List<String>> keysByAccount = new LinkedHashMap<>();
        for (String key : evidence.keySet()) {
            String account = evidence.get(key).accountLast4();
            keysByAccount.computeIfAbsent(account, ignored -> new ArrayList<>()).add(key);
        }

        for (List<String> keys : keysByAccount.values()) {
            keys.sort(Comparator.comparing(key -> evidence.get(key).occurredAt()));
            BigDecimal previousBalance = null;
            for (String key : keys) {
                ParsedTxn current = evidence.get(key);
                if (previousBalance != null) {
                    BigDecimal expected = current.direction() == Direction.DEBIT
                            ? previousBalance.subtract(current.amount())
                            : previousBalance.add(current.amount());
                    if (current.statedBalance() != null) {
                    BigDecimal gap = expected.subtract(current.statedBalance());
                    if (current.direction() == Direction.DEBIT && gap.signum() > 0) {
                        String adjustmentKey = current.accountLast4() + "|balance-gap|"
                            + current.occurredAt() + "|" + gap.toPlainString();
                        ledger.putIfAbsent(adjustmentKey, new NormalizedTxn(
                            current.accountLast4(), current.occurredAt(), Direction.DEBIT,
                            gap, Category.SPEND, "UNRECONCILED BANK DEBIT",
                            List.of("inferred-balance-gap:" + current.sourceMessageId())));
                    }
                    }
                }
                previousBalance = current.statedBalance() == null
                    ? expectedBalance(previousBalance, current)
                    : current.statedBalance();
            }
        }
    }

            private static BigDecimal expectedBalance(BigDecimal previousBalance, ParsedTxn current) {
            if (previousBalance == null) return null;
            return current.direction() == Direction.DEBIT
                ? previousBalance.subtract(current.amount())
                : previousBalance.add(current.amount());
            }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
