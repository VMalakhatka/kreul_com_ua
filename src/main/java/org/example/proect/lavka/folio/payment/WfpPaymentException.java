package org.example.proect.lavka.folio.payment;

/** Fixed, non-sensitive codes only. */
public class WfpPaymentException extends RuntimeException {
    public final int status;
    public WfpPaymentException(int status, String code) { super(code); this.status = status; }
    static void require(boolean condition, String code) {
        if (!condition) throw new WfpPaymentException(409, code);
    }
}
