# Track Flow Teardown

**Priyesh Singh | Simplify Money Backend Engineer/Intern assignment**

## 1. Flow

```text
Raw bank SMS/email
  -> RawMessage
  -> format-specific parser
  -> ParsedTxn
  -> normalized transaction identity + deduplication
  -> category classification
  -> LedgerStore
  -> ledger.json, summary.json, reconciliation.json
```

The Track screen is a read-only view over this pipeline. Each row shows:

- merchant
- positive transaction amount
- debit or credit direction
- category
- transaction timestamp from the bank
- supporting source message IDs

Transfers are excluded from spend and income totals. MICRO transactions are rolled into `micro_count` and `micro_total`. Any bank-stated balance that cannot be explained by parsed transactions appears in `reconciliation.json`.

## 2. Stage Guarantees

| Stage | Guarantee | Failure prevented |
|---|---|---|
| Format-specific parser | Extracts the transaction amount, never a quoted balance or available limit | A Rs 5 transaction being shown as a Rs 92,213.10 spend |
| Normalization and deduplication | One real-world transaction produces one ledger entry, keyed on normalized identity and transaction instant | The same transaction appearing twice through SMS and email with different timezone offsets |
| Classification | `SPEND`, `INCOME`, `MICRO`, or `TRANSFER` is assigned once and deterministically | Own-account transfers inflating spend or income |
| LedgerStore | Stores every transaction with its source message IDs | Numbers on screen that cannot be traced to a source message |
| Reconciliation | Reports any bank-stated balance not explained by parsed transactions | A silently wrong closing balance |

## 3. Track Screen Design Decisions

- **Positive amount plus separate direction:** The sign is not baked into the amount, so a parser error cannot silently turn a credit into a debit through arithmetic. Direction is explicit and testable.
- **Bank timestamp, not ingestion time:** Rows use when the bank says the transaction happened. This also enables SMS/email deduplication.
- **Source message IDs on every row:** Anyone can answer why a transaction exists by tracing it to the raw message.
- **Transfers excluded from spend and income:** Moving money between own accounts is neither spending nor earning, so it must not change either total.
- **MICRO rolled into `micro_count` and `micro_total`:** Small transactions remain accounted for without cluttering the main list or distorting category totals.
- **Unexplained balances reported explicitly:** On corpus-a, the Rs 7,500 balance drop that no parsed transaction explains is reported in `reconciliation.json` instead of being silently absorbed.

## 4. Where the Flow Can Lose User Trust

1. **A wrong amount on the first screen:** Showing Rs 92,213.10 instead of Rs 5 is highly visible and can look like fraud.
2. **Duplicates:** Connecting both SMS and email doubles entries unless the deduplication key uses the transaction instant.
3. **A balance that disagrees with the bank app:** Users trust the bank app over a tracker, so a mismatch must be surfaced rather than hidden.
4. **Transfers counted as spend:** Monthly totals become misleading even when each individual row is correct.

## 5. Results on corpus-a

The pipeline processes 522 raw messages and produces 257 normalized transactions.

- Account 4821: 146 transactions, closing balance Rs 41,126.34, reconciled.
- Account 9075: 91 transactions, closing balance Rs 51,210.63, reconciled.
- One unexplained Rs 7,500 balance drop is reported in `reconciliation.json`.

## 6. Next Improvements

- Add a confidence flag for transactions parsed from bank templates not seen in the corpus, so the Track screen can mark them as needing review.
- Allow a user to correct a parse and turn each correction into a regression test.
- Display reconciliation discrepancies directly inside the Track screen, not only in a JSON report.
- Replace the in-memory document-store reference implementation with DynamoDB for production deployment.
