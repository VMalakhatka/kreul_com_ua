package org.example.proect.lavka.folio.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Receipt of money, independent of invoices and their later distribution. */
public record WfpPaymentCommand(String operationKey, String sourceScope, String merchantHash,
        String reference, long orderId, long amountCents, String currency, String confirmedAt,
        String payerKey, String expectedRevision) {
    static final Pattern HEX = Pattern.compile("[a-f0-9]{64}");
    public void validate(WfpPaymentProperties p, Instant now) {
        try {
            if (sourceScope == null || !sourceScope.matches("[a-zA-Z0-9._-]{1,64}")
                    || !sourceScope.equals(p.getSourceScope()) || merchantHash == null
                    || !HEX.matcher(merchantHash).matches() || !merchantHash.equals(p.getMerchantHash())
                    || orderId < 1 || orderId > 999999999999L
                    || reference == null || !reference.matches(orderId + "_woo_w4p_[0-9]{10}")
                    || amountCents < 1 || amountCents > 999999999L || !"UAH".equals(currency)
                    || payerKey == null || !payerKey.equals(payerKey.trim()) || !payerKey.matches("[\\p{L}\\p{N} ._-]{1,8}")
                    || confirmedAt == null || !confirmedAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
                    || !key(sourceScope, merchantHash, reference).equals(operationKey)) throw new IllegalArgumentException();
            Instant when = Instant.parse(confirmedAt);
            if (when.isBefore(Instant.parse(p.getStartAt())) || when.isAfter(now.plusSeconds(60))) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new WfpPaymentException(422, "invalid_payment_command"); }
    }
    public String requestHash() {
        return hash(String.join("|", "v1", sourceScope, merchantHash, reference, Long.toString(orderId),
                Long.toString(amountCents), currency, confirmedAt, payerKey));
    }
    public static String key(String scope, String merchant, String reference) { return hash(scope + "|" + merchant + "|" + reference); }
    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
