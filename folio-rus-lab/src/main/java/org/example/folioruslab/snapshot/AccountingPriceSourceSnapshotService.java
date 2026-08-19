package org.example.folioruslab.snapshot;

import jakarta.annotation.PreDestroy;
import org.example.folioruslab.sql.LabBusyException;
import org.example.folioruslab.sql.LabOperationGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@Service
public final class AccountingPriceSourceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(
            AccountingPriceSourceSnapshotService.class
    );
    private static final int MAX_REPORTED_CHANGED_SKUS = 500;

    private final AccountingPriceSourceSnapshotGateway gateway;
    private final LabOperationGate operationGate;
    private final Clock clock;
    private final ExecutorService executor;
    private final AtomicReference<AccountingPriceSourceSnapshotStatus> status =
            new AtomicReference<>(AccountingPriceSourceSnapshotStatus.idle());
    private final Map<Integer, AccountingPriceSourceSnapshot> baselines = new HashMap<>();

    @Autowired
    public AccountingPriceSourceSnapshotService(
            AccountingPriceSourceSnapshotGateway gateway,
            LabOperationGate operationGate
    ) {
        this(gateway, operationGate, Clock.systemUTC());
    }

    AccountingPriceSourceSnapshotService(
            AccountingPriceSourceSnapshotGateway gateway,
            LabOperationGate operationGate,
            Clock clock
    ) {
        this.gateway = gateway;
        this.operationGate = operationGate;
        this.clock = clock;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "folio-rus-accounting-source-snapshot");
            thread.setDaemon(true);
            return thread;
        });
    }

    public AccountingPriceSourceSnapshotStatus start(
            AccountingPriceSourceSnapshotRequest request
    ) {
        if (!operationGate.tryAcquire()) {
            throw new LabBusyException();
        }
        Progress progress = new Progress(
                UUID.randomUUID().toString(), request.warehouseId(), now()
        );
        progress.accepted = true;
        progress.running = true;
        progress.status = "QUEUED";
        publish(progress);
        AccountingPriceSourceSnapshotStatus accepted = status.get();
        try {
            executor.execute(() -> run(progress));
        } catch (RejectedExecutionException exception) {
            operationGate.release();
            progress.ok = false;
            progress.accepted = false;
            progress.running = false;
            progress.status = "FAILED";
            progress.completedAt = now();
            progress.error = "The laboratory snapshot executor rejected the job";
            publish(progress);
        }
        return accepted;
    }

    public AccountingPriceSourceSnapshotStatus status() {
        return status.get();
    }

    private void run(Progress progress) {
        log.info(
                "ACCOUNTING_SOURCE_SNAPSHOT_START jobId={} warehouseId={}",
                progress.jobId, progress.warehouseId
        );
        try {
            progress.accepted = false;
            progress.status = "CAPTURING";
            publish(progress);

            AccountingPriceSourceSnapshot current = gateway.capture(progress.warehouseId);
            AccountingPriceSourceSnapshot previous;
            synchronized (baselines) {
                previous = baselines.get(progress.warehouseId);
                if (previous == null) {
                    baselines.put(progress.warehouseId, current);
                }
            }
            progress.apply(current, previous);
            progress.ok = true;
            progress.running = false;
            progress.completedAt = now();
            progress.status = previous == null
                    ? "BASELINE_CREATED"
                    : (progress.dirtySkuCount == 0
                    && progress.newSkuCount == 0
                    && progress.removedSkuCount == 0
                    ? "UNCHANGED" : "CHANGES_DETECTED");
            log.info(
                    "ACCOUNTING_SOURCE_SNAPSHOT_COMPLETE jobId={} warehouseId={} status={} "
                            + "skus={} movements={} dirty={} new={} removed={}",
                    progress.jobId, progress.warehouseId, progress.status,
                    progress.skuCount, progress.movementCount,
                    progress.dirtySkuCount, progress.newSkuCount,
                    progress.removedSkuCount
            );
        } catch (RuntimeException exception) {
            progress.ok = false;
            progress.accepted = false;
            progress.running = false;
            progress.completedAt = now();
            progress.status = "FAILED";
            progress.error = exception.getMessage() == null
                    ? "The Paint_Rus source snapshot failed"
                    : exception.getMessage();
            log.error(
                    "ACCOUNTING_SOURCE_SNAPSHOT_FAILED jobId={} warehouseId={} type={}",
                    progress.jobId, progress.warehouseId, exception.getClass().getName()
            );
        } finally {
            operationGate.release();
            publish(progress);
        }
    }

    private void publish(Progress progress) {
        status.set(new AccountingPriceSourceSnapshotStatus(
                progress.ok,
                progress.accepted,
                progress.running,
                progress.jobId,
                progress.status,
                progress.warehouseId,
                progress.startedAt,
                progress.completedAt,
                AccountingPriceSourceSnapshot.SNAPSHOT_VERSION,
                progress.warehouseDigest,
                progress.previousWarehouseDigest,
                progress.skuCount,
                progress.movementCount,
                progress.priceRuleCount,
                progress.ignoredOrphanMovementCount,
                progress.ignoredOrphanPriceRuleCount,
                progress.comparedToPrevious,
                progress.unchangedSkuCount,
                progress.dirtySkuCount,
                progress.newSkuCount,
                progress.removedSkuCount,
                progress.changedSkusTruncated,
                progress.changedSkus,
                progress.error
        ));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    private static final class Progress {
        private final String jobId;
        private final int warehouseId;
        private final LocalDateTime startedAt;
        private boolean ok = true;
        private boolean accepted;
        private boolean running;
        private String status;
        private LocalDateTime completedAt;
        private String warehouseDigest;
        private String previousWarehouseDigest;
        private int skuCount;
        private long movementCount;
        private long priceRuleCount;
        private long ignoredOrphanMovementCount;
        private long ignoredOrphanPriceRuleCount;
        private boolean comparedToPrevious;
        private int unchangedSkuCount;
        private int dirtySkuCount;
        private int newSkuCount;
        private int removedSkuCount;
        private boolean changedSkusTruncated;
        private List<String> changedSkus = List.of();
        private String error;

        private Progress(String jobId, int warehouseId, LocalDateTime startedAt) {
            this.jobId = jobId;
            this.warehouseId = warehouseId;
            this.startedAt = startedAt;
        }

        private void apply(
                AccountingPriceSourceSnapshot current,
                AccountingPriceSourceSnapshot previous
        ) {
            warehouseDigest = current.warehouseDigest();
            skuCount = current.skuDigests().size();
            movementCount = current.movementCount();
            priceRuleCount = current.priceRuleCount();
            ignoredOrphanMovementCount = current.ignoredOrphanMovementCount();
            ignoredOrphanPriceRuleCount = current.ignoredOrphanPriceRuleCount();
            if (previous == null) {
                return;
            }
            comparedToPrevious = true;
            previousWarehouseDigest = previous.warehouseDigest();
            TreeSet<String> allSkus = new TreeSet<>();
            allSkus.addAll(previous.skuDigests().keySet());
            allSkus.addAll(current.skuDigests().keySet());
            ArrayList<String> changed = new ArrayList<>();
            for (String sku : allSkus) {
                String before = previous.skuDigests().get(sku);
                String after = current.skuDigests().get(sku);
                if (before == null) {
                    newSkuCount++;
                    addChanged(changed, sku);
                } else if (after == null) {
                    removedSkuCount++;
                    addChanged(changed, sku);
                } else if (!before.equals(after)) {
                    dirtySkuCount++;
                    addChanged(changed, sku);
                } else {
                    unchangedSkuCount++;
                }
            }
            changedSkus = List.copyOf(changed);
        }

        private void addChanged(ArrayList<String> changed, String sku) {
            if (changed.size() < MAX_REPORTED_CHANGED_SKUS) {
                changed.add(sku);
            } else {
                changedSkusTruncated = true;
            }
        }
    }
}
