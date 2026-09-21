package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice - "Rs.", "Rs ", "INR " - and strips
 * the thousands separators before handing back a BigDecimal.
 */
public final class Amounts {

    private Amounts() {}

    private static final Pattern AMOUNT = Pattern.compile(
            "(?:Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.[0-9]{2})?)");

    private static final Pattern BALANCE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.[0-9]{2})?)",
            Pattern.CASE_INSENSITIVE);

    /** The transaction amount: the first rupee figure in the message that is not a balance. */
    public static BigDecimal first(String body) {
        Matcher m = AMOUNT.matcher(body);
        while (m.find()) {
            String candidate = m.group(1);
            int start = m.start();
            String before = body.substring(Math.max(0, start - 30), start).toLowerCase();
            if (before.contains("avl bal")
                    || before.contains("available balance")
                    || before.contains("balavl")
                    || before.contains("avl limit")) {
                continue;
            }
            return toDecimal(candidate);
        }
        return null;
    }

    /** The balance the bank quoted, if it quoted one. */
    public static BigDecimal statedBalance(String body) {
        Matcher m = BALANCE.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    private static BigDecimal toDecimal(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }
}
