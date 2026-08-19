package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.Capture;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductCard;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.Change;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.ExistingItem;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.Item;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.Publish;
import org.example.proect.lavka.dto.folio.FolioProductSnapshotRefreshRequest;
import org.example.proect.lavka.dto.folio.FolioProductSnapshotStatusResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class FolioProductSnapshotService {

    private final FolioProductSnapshotSourceDao sourceDao;
    private final FolioProductSnapshotDao snapshotDao;
    private final FolioAccountingPriceDao accountingPriceDao;
    private final FolioProductEconomicsCalculator economicsCalculator;
    private final TaskExecutor executor;
    private final Clock clock;
    private final boolean enabled;
    private final int defaultHorizonMonths;
    private final int queryTimeoutSeconds;
    private final int lockTimeoutMs;
    private final int leaseSeconds;
    private final TransactionTemplate sourceTransaction;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<FolioProductSnapshotStatusResponse> live =
            new AtomicReference<>();

    public FolioProductSnapshotService(
            FolioProductSnapshotSourceDao sourceDao,
            FolioProductSnapshotDao snapshotDao,
            FolioAccountingPriceDao accountingPriceDao,
            FolioProductEconomicsCalculator economicsCalculator,
            @Qualifier("folioProductSnapshotExecutor") TaskExecutor executor,
            @Qualifier("folioProductSnapshotClock") Clock clock,
            @Qualifier("mssqlTransactionManager") PlatformTransactionManager txManager,
            @Value("${lavka.folio.product-snapshot.enabled:true}") boolean enabled,
            @Value("${lavka.folio.product-snapshot.default-horizon-months:24}")
            int defaultHorizonMonths,
            @Value("${lavka.folio.product-snapshot.query-timeout-seconds:600}")
            int queryTimeoutSeconds,
            @Value("${lavka.folio.product-snapshot.lock-timeout-ms:5000}") int lockTimeoutMs,
            @Value("${lavka.folio.product-snapshot.lease-seconds:3600}") int leaseSeconds) {
        this.sourceDao = sourceDao;
        this.snapshotDao = snapshotDao;
        this.accountingPriceDao = accountingPriceDao;
        this.economicsCalculator = economicsCalculator;
        this.executor = executor;
        this.clock = clock;
        this.enabled = enabled;
        this.defaultHorizonMonths = Math.max(12, Math.min(36, defaultHorizonMonths));
        this.queryTimeoutSeconds = Math.max(30, queryTimeoutSeconds);
        this.lockTimeoutMs = Math.max(0, lockTimeoutMs);
        this.leaseSeconds = Math.max(300, leaseSeconds);
        this.sourceTransaction = new TransactionTemplate(txManager);
        this.sourceTransaction.setReadOnly(true);
        this.sourceTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.sourceTransaction.setTimeout(this.queryTimeoutSeconds);
    }

    public FolioProductSnapshotStatusResponse request(
            FolioProductSnapshotRefreshRequest request) {
        if (!enabled) {
            throw new FolioAccountingPriceDisabledException(
                    "PRODUCT_SNAPSHOT_DISABLED", "Folio product snapshot API is disabled");
        }
        if (!running.compareAndSet(false, true)) {
            return withAccepted(status(), false);
        }
        int warehouseId = request.warehouseId();
        int horizonMonths = request.effectiveHorizonMonths(defaultHorizonMonths);
        String sourceDatabase = sourceDao.currentDatabaseName();
        LocalDateTime started = LocalDateTime.now(clock);
        live.set(new FolioProductSnapshotStatusResponse(
                true, true, true, null, "QUEUED", "QUEUED",
                sourceDatabase, warehouseId, horizonMonths, started, null,
                0, 0, 0, 0, 0, 0, 0, null, null));
        try {
            executor.execute(() -> run(sourceDatabase, warehouseId, horizonMonths, started));
            return live.get();
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    public FolioProductSnapshotStatusResponse status() {
        FolioProductSnapshotStatusResponse current = live.get();
        if (current != null) return withAccepted(current, false);
        return snapshotDao.latest().map(g -> new FolioProductSnapshotStatusResponse(
                "ACTIVE".equals(g.status()), false, false, g.id(), g.status(), g.status(),
                g.sourceDatabase(), g.warehouseId(), g.horizonMonths(), g.startedAt(),
                g.completedAt(), g.totalProducts(), g.movementRows(), g.monthlyMetricRows(),
                g.unverified(), g.dirty(), g.created(), g.removed(),
                g.warehouseDigest(), g.error()
        )).orElseGet(() -> new FolioProductSnapshotStatusResponse(
                true, false, false, null, "NOT_READY", "IDLE", null, null,
                null, null, null, 0, 0, 0, 0, 0, 0, 0, null, null));
    }

    private void run(String sourceDatabase, int warehouseId, int horizonMonths,
                     LocalDateTime startedAt) {
        String owner = UUID.randomUUID().toString();
        String scope = sourceDatabase + ":" + warehouseId;
        Long generationId = null;
        try {
            if (!snapshotDao.tryAcquireLease(scope, owner, leaseSeconds)) {
                throw new IllegalStateException("Another product snapshot owns this warehouse lease");
            }
            generationId = snapshotDao.createGeneration(
                    sourceDatabase, warehouseId, horizonMonths, "MANUAL", startedAt);
            setRunning(generationId, "SOURCE_CAPTURE", sourceDatabase,
                    warehouseId, horizonMonths, startedAt);

            LocalDate asOfDate = LocalDate.now(clock);
            LocalDate horizonStart = asOfDate.minusMonths(horizonMonths - 1L)
                    .withDayOfMonth(1);
            Capture capture = sourceTransaction.execute(status -> {
                accountingPriceDao.acquireRecalculationMutex(lockTimeoutMs);
                return sourceDao.capture(warehouseId, horizonStart, asOfDate,
                        queryTimeoutSeconds);
            });
            if (capture == null) throw new IllegalStateException("Folio source capture returned no data");
            if (!sourceDatabase.equals(capture.warehouse().databaseName())) {
                throw new IllegalStateException("Folio database changed during snapshot capture");
            }

            setRunning(generationId, "ECONOMIC_CALCULATION", sourceDatabase,
                    warehouseId, horizonMonths, startedAt);
            var economics = economicsCalculator.calculate(capture, horizonStart, asOfDate);
            Map<String, ExistingItem> existing = snapshotDao.findExisting(
                    sourceDatabase, warehouseId);
            LocalDateTime calculatedAt = LocalDateTime.now(clock);
            Classification classification = classify(
                    generationId, sourceDatabase, warehouseId,
                    capture.products(), existing, calculatedAt);

            setRunning(generationId, "PUBLISHING", sourceDatabase,
                    warehouseId, horizonMonths, startedAt);
            snapshotDao.publish(new Publish(
                    generationId, sourceDatabase, warehouseId,
                    capture.warehouseDigest(), capture.movementRows(),
                    classification.items(), classification.changes(),
                    economics.monthly(), economics.current(), economics.alerts(),
                    classification.unverified(), classification.dirty(),
                    classification.created(), classification.removed(), calculatedAt));

            live.set(new FolioProductSnapshotStatusResponse(
                    true, false, false, generationId, "ACTIVE", "COMPLETED",
                    sourceDatabase, warehouseId, horizonMonths, startedAt, calculatedAt,
                    capture.products().size(), capture.movementRows(), economics.monthly().size(),
                    classification.unverified(), classification.dirty(),
                    classification.created(), classification.removed(),
                    capture.warehouseDigest(), null));
            log.info("[folio.product.snapshot] generation={} db={} warehouse={} products={} movements={} monthly={} unverified={} dirty={} new={} removed={}",
                    generationId, sourceDatabase, warehouseId, capture.products().size(),
                    capture.movementRows(), economics.monthly().size(),
                    classification.unverified(), classification.dirty(),
                    classification.created(), classification.removed());
        } catch (Exception e) {
            LocalDateTime failedAt = LocalDateTime.now(clock);
            if (generationId != null) {
                try { snapshotDao.failGeneration(generationId, rootMessage(e), failedAt); }
                catch (Exception failure) { e.addSuppressed(failure); }
            }
            live.set(new FolioProductSnapshotStatusResponse(
                    false, false, false, generationId, "FAILED", "FAILED",
                    sourceDatabase, warehouseId, horizonMonths, startedAt, failedAt,
                    0, 0, 0, 0, 0, 0, 0, null, rootMessage(e)));
            log.error("[folio.product.snapshot] failed generation={} db={} warehouse={}",
                    generationId, sourceDatabase, warehouseId, e);
        } finally {
            try { snapshotDao.releaseLease(scope, owner); }
            catch (Exception e) { log.error("[folio.product.snapshot] lease release failed scope={}", scope, e); }
            running.set(false);
        }
    }

    private Classification classify(long generationId, String db, int warehouseId,
                                    List<ProductCard> cards,
                                    Map<String, ExistingItem> existing,
                                    LocalDateTime at) {
        boolean firstBaseline = existing.isEmpty();
        List<Item> items = new ArrayList<>(cards.size() + existing.size());
        List<Change> changes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int unverified = 0, dirty = 0, created = 0, removed = 0;
        for (ProductCard card : cards) {
            seen.add(card.sku());
            ExistingItem before = existing.get(card.sku());
            String state;
            String change = null;
            if (before == null) {
                state = firstBaseline ? "UNVERIFIED" : "NEW";
                change = firstBaseline ? "BASELINE" : "ADDED";
            } else if (!before.present()) {
                state = "NEW";
                change = "RESTORED";
            } else if (!card.sourceDigest().equals(before.observedDigest())) {
                state = "FAILED".equals(before.state()) ? "FAILED" : "DIRTY";
                change = "CHANGED";
            } else if (before.appliedDigest() != null
                    && before.appliedDigest().equals(card.sourceDigest())) {
                state = "VERIFIED";
            } else {
                state = before.state();
            }
            if ("UNVERIFIED".equals(state)) unverified++;
            if ("DIRTY".equals(state) || "FAILED".equals(state)) dirty++;
            if ("NEW".equals(state)) created++;
            LocalDateTime firstSeen = before == null ? at : before.firstSeenAt();
            items.add(new Item(db, warehouseId, card.sku(), card.productName(),
                    card.sourceDigest(), before == null ? null : before.appliedDigest(),
                    state, true, card.movementCount(), card.minRecno(), card.maxRecno(),
                    card.firstMovementDate(), card.lastMovementDate(), card.priceRuleCount(),
                    firstSeen, at, at, before == null ? null : before.appliedAt(), generationId,
                    "FAILED".equals(state) && before != null ? before.lastError() : null));
            if (change != null) {
                changes.add(new Change(generationId, db, warehouseId, card.sku(), change,
                        before == null ? null : before.observedDigest(),
                        card.sourceDigest(), at));
            }
        }
        for (ExistingItem before : existing.values()) {
            if (seen.contains(before.sku()) || !before.present()) continue;
            removed++;
            items.add(new Item(db, warehouseId, before.sku(), before.productName(), null,
                    before.appliedDigest(), "REMOVED", false, before.movementCount(),
                    before.minRecno(), before.maxRecno(), before.firstMovementDate(),
                    before.lastMovementDate(), before.priceRuleCount(),
                    before.firstSeenAt(), at, at, before.appliedAt(),
                    generationId, before.lastError()));
            changes.add(new Change(generationId, db, warehouseId, before.sku(),
                    "REMOVED", before.observedDigest(), null, at));
        }
        return new Classification(List.copyOf(items), List.copyOf(changes),
                unverified, dirty, created, removed);
    }

    private void setRunning(long generationId, String phase, String db,
                            int warehouseId, int horizonMonths, LocalDateTime started) {
        live.set(new FolioProductSnapshotStatusResponse(
                true, false, true, generationId, "BUILDING", phase, db,
                warehouseId, horizonMonths, started, null,
                0, 0, 0, 0, 0, 0, 0, null, null));
    }

    private static FolioProductSnapshotStatusResponse withAccepted(
            FolioProductSnapshotStatusResponse value, boolean accepted) {
        return new FolioProductSnapshotStatusResponse(
                value.ok(), accepted, value.running(), value.generationId(), value.status(),
                value.phase(), value.sourceDatabase(), value.warehouseId(),
                value.horizonMonths(), value.startedAt(), value.completedAt(),
                value.totalProducts(), value.movementRows(), value.monthlyMetricRows(),
                value.unverifiedProducts(), value.dirtyProducts(), value.newProducts(),
                value.removedProducts(), value.warehouseDigest(), value.error());
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String value = current.getMessage();
        return value == null || value.isBlank() ? current.getClass().getSimpleName() : value;
    }

    private record Classification(List<Item> items, List<Change> changes,
                                  int unverified, int dirty, int created, int removed) { }
}
