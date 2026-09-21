package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Indexed document-store implementation used by the local pipeline and tests. */
public final class InMemoryDocumentStore implements DocumentStore {

    private final Map<String, NormalizedTxn> byTransaction = new LinkedHashMap<>();
    private final Map<String, String> transactionByMessage = new LinkedHashMap<>();
    private final Map<String, List<String>> transactionKeysByAccountMonth = new LinkedHashMap<>();
    private final Map<String, Map<Category, BigDecimal>> totalsByAccount = new LinkedHashMap<>();

    @Override
    public synchronized void save(NormalizedTxn txn) {
        String key = transactionKey(txn);
        NormalizedTxn existing = byTransaction.get(key);
        if (existing != null) {
            txn = merge(existing, txn);
        } else {
            byTransaction.put(key, txn);
            transactionKeysByAccountMonth
                    .computeIfAbsent(accountMonthKey(txn), ignored -> new ArrayList<>())
                    .add(key);
            addTotals(txn, BigDecimal.ONE);
        }
        byTransaction.put(key, txn);
        for (String messageId : txn.sourceMessageIds()) {
            transactionByMessage.put(messageId, key);
        }
    }

    @Override
    public synchronized List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        return transactionKeysByAccountMonth
                .getOrDefault(accountLast4 + "|" + month, List.of())
                .stream()
                .map(byTransaction::get)
                .sorted(Comparator.comparing(NormalizedTxn::occurredAt).reversed())
                .toList();
    }

    @Override
    public synchronized Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            totals.put(category, BigDecimal.ZERO.setScale(2));
        }
        totals.putAll(totalsByAccount.getOrDefault(accountLast4, Map.of()));
        return Collections.unmodifiableMap(totals);
    }

    @Override
    public synchronized Optional<NormalizedTxn> byMessageId(String messageId) {
        String key = transactionByMessage.get(messageId);
        return key == null ? Optional.empty() : Optional.ofNullable(byTransaction.get(key));
    }

    @Override
    public synchronized List<NormalizedTxn> all() {
        return List.copyOf(byTransaction.values());
    }

    private void addTotals(NormalizedTxn txn, BigDecimal multiplier) {
        Map<Category, BigDecimal> totals = totalsByAccount
                .computeIfAbsent(txn.accountLast4(), ignored -> new LinkedHashMap<>());
        totals.merge(txn.category(), txn.amount().multiply(multiplier), BigDecimal::add);
    }

    private NormalizedTxn merge(NormalizedTxn existing, NormalizedTxn incoming) {
        List<String> ids = new ArrayList<>(existing.sourceMessageIds());
        ids.addAll(incoming.sourceMessageIds());
        return new NormalizedTxn(existing.accountLast4(), existing.occurredAt(),
                existing.direction(), existing.amount(), existing.category(), existing.merchant(),
                ids.stream().distinct().sorted().toList());
    }

    private static String transactionKey(NormalizedTxn txn) {
        return txn.accountLast4() + "|" + txn.occurredAt().toInstant() + "|"
                + txn.direction() + "|" + txn.amount().toPlainString() + "|"
                + txn.category() + "|" + txn.merchant().trim().toUpperCase();
    }

    private static String accountMonthKey(NormalizedTxn txn) {
        return txn.accountLast4() + "|" + YearMonth.from(txn.occurredAt());
    }
}
