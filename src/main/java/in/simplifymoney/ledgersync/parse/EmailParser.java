package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * The corpus contains HDFC and ICICI email alerts with the same semantics as the
 * SMS variants, but with the account/amount information embedded in plain text.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern HDFC = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with "
                    + "(?:Rs\\.?|INR)\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{2})?)\\.?\s*\n?"
                    + "Merchant / Remarks: (?<merchant>.+?)\s*\nTransaction reference:",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ICICI = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with "
                    + "(?:Rs\\.?|INR)\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{2})?)\\.?\s*\n?"
                    + "Merchant / Remarks: (?<merchant>.+?)\s*\nTransaction reference:",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final DateTimeFormatter EMAIL_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher hdfc = HDFC.matcher(m.body());
        if (hdfc.find()) {
            return build(m, hdfc.group("acct"), hdfc.group("dir"),
                    hdfc.group("amount"), hdfc.group("merchant"));
        }

        Matcher icici = ICICI.matcher(m.body());
        if (icici.find()) {
            return build(m, icici.group("acct"), icici.group("dir"),
                    icici.group("amount"), icici.group("merchant"));
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(RawMessage m, String acct, String dir,
                                     String amountText, String merchant) {
        String dateLine = m.body().lines()
                .filter(line -> line.startsWith("Date:"))
                .findFirst()
                .orElse(null);
        if (dateLine == null) return Optional.empty();

        String dateOnly = dateLine.substring("Date:".length()).trim();
        OffsetDateTime at;
        try {
            at = OffsetDateTime.parse(dateOnly, EMAIL_DATE);
        } catch (Exception e) {
            return Optional.empty();
        }

        BigDecimal amount = Amounts.first("INR " + amountText);
        if (amount == null) return Optional.empty();

        Direction d = "debited".equalsIgnoreCase(dir) ? Direction.DEBIT : Direction.CREDIT;
        return Optional.of(new ParsedTxn(acct, at, d, amount,
                merchant.trim(), Amounts.statedBalance(m.body()), m.messageId()));
    }
}
