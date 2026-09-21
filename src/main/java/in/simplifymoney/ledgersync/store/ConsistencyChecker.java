package in.simplifymoney.ledgersync.store;

import java.util.List;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new java.util.ArrayList<>();
        java.util.Map<String, in.simplifymoney.ledgersync.model.NormalizedTxn> documents =
                new java.util.LinkedHashMap<>();
        for (var txn : documents()) {
            for (String messageId : txn.sourceMessageIds()) {
                documents.put(messageId, txn);
            }
        }

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var txn : sql.all()) {
            for (String messageId : txn.sourceMessageIds()) {
                seen.add(messageId);
                var document = documents.get(messageId);
                if (document == null) {
                    divergences.add(new Divergence(messageId, describe(txn), "missing"));
                } else if (!txn.equals(document)) {
                    divergences.add(new Divergence(messageId, describe(txn), describe(document)));
                }
            }
        }
        for (var entry : documents.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                divergences.add(new Divergence(entry.getKey(), "missing", describe(entry.getValue())));
            }
        }
        return divergences;
    }

    private List<in.simplifymoney.ledgersync.model.NormalizedTxn> documents() {
        try {
            return documents.all();
        } catch (UnsupportedOperationException e) {
            return sql.all().stream()
                    .flatMap(txn -> txn.sourceMessageIds().stream()
                            .map(documents::byMessageId)
                            .flatMap(java.util.Optional::stream))
                    .distinct().toList();
        }
    }

    private static String describe(in.simplifymoney.ledgersync.model.NormalizedTxn txn) {
        return txn.accountLast4() + "|" + txn.occurredAt() + "|" + txn.direction()
                + "|" + txn.amount() + "|" + txn.category() + "|" + txn.merchant()
                + "|" + txn.sourceMessageIds();
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
