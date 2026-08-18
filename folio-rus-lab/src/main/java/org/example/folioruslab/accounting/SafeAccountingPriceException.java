package org.example.folioruslab.accounting;

final class SafeAccountingPriceException extends RuntimeException {

    SafeAccountingPriceException(String message) {
        super(message);
    }

    SafeAccountingPriceException(String message, Throwable cause) {
        super(message, cause);
    }
}
