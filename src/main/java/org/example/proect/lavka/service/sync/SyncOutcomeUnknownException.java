package org.example.proect.lavka.service.sync;

/** A dispatched Woo write has no durable, complete receipt. Never replay it automatically. */
public class SyncOutcomeUnknownException extends RuntimeException {
    public SyncOutcomeUnknownException() { super("WOO_WRITE_OUTCOME_UNKNOWN"); }
}
