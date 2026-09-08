package org.example.proect.lavka.dao.folio;

import org.springframework.dao.QueryTimeoutException;
import java.util.function.LongSupplier;

/** Request-local cooperative deadline; JDBC cancellation still depends on driver/network health. */
public final class FolioProfitReadBudget implements AutoCloseable {
    private static final ThreadLocal<FolioProfitReadBudget> CURRENT = new ThreadLocal<>();
    private final FolioProfitReadBudget previous;
    private final LongSupplier clock;
    private final long deadline;
    private final int querySeconds;

    public FolioProfitReadBudget(int totalSeconds, int querySeconds) {
        this(totalSeconds, querySeconds, System::nanoTime);
    }
    FolioProfitReadBudget(int totalSeconds, int querySeconds, LongSupplier clock) {
        this.clock = clock; this.querySeconds = Math.max(1, querySeconds);
        this.deadline = clock.getAsLong() + Math.max(1, totalSeconds) * 1_000_000_000L;
        this.previous = CURRENT.get(); CURRENT.set(this);
    }
    public static int remainingQuerySeconds() {
        var budget = CURRENT.get();
        if (budget == null) return 30;
        long remaining = budget.deadline - budget.clock.getAsLong();
        if (remaining <= 0) throw new QueryTimeoutException("PROFIT_REPORT_READ_BUDGET_EXHAUSTED");
        return (int) Math.min(budget.querySeconds, Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L));
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
