package org.example.folioruslab.accounting;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@Service
public final class SafeAccountingPricePreviewService {

    private static final Logger log = LoggerFactory.getLogger(
            SafeAccountingPricePreviewService.class
    );
    private static final int MAX_REPORTED_PROBLEMS = 1000;
    private static final int STATUS_UPDATE_INTERVAL = 25;

    private final SafeAccountingPriceGateway gateway;
    private final LabOperationGate operationGate;
    private final Clock clock;
    private final ExecutorService executor;
    private final AtomicReference<SafeAccountingPricePreviewStatus> status =
            new AtomicReference<>(SafeAccountingPricePreviewStatus.idle());

    @Autowired
    public SafeAccountingPricePreviewService(
            SafeAccountingPriceGateway gateway,
            LabOperationGate operationGate
    ) {
        this(gateway, operationGate, Clock.systemUTC());
    }

    SafeAccountingPricePreviewService(
            SafeAccountingPriceGateway gateway,
            LabOperationGate operationGate,
            Clock clock
    ) {
        this.gateway = gateway;
        this.operationGate = operationGate;
        this.clock = clock;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "folio-rus-safe-accounting-preview");
            thread.setDaemon(true);
            return thread;
        });
    }

    public SafeAccountingPricePreviewStatus start(SafeAccountingPricePreviewRequest request) {
        if (!operationGate.tryAcquire()) {
            throw new LabBusyException();
        }

        Progress progress = new Progress(
                UUID.randomUUID().toString(), request.warehouseId(), now()
        );
        progress.status = "QUEUED";
        progress.running = true;
        progress.accepted = true;
        publish(progress);
        SafeAccountingPricePreviewStatus acceptedStatus = status.get();
        try {
            executor.execute(() -> run(progress));
        } catch (RejectedExecutionException exception) {
            operationGate.release();
            progress.running = false;
            progress.accepted = false;
            progress.ok = false;
            progress.status = "FAILED";
            progress.completedAt = now();
            progress.error = "The laboratory preview executor rejected the job";
            publish(progress);
            return status.get();
        }
        return acceptedStatus;
    }

    public SafeAccountingPricePreviewStatus status() {
        return status.get();
    }

    private void run(Progress progress) {
        log.info(
                "SAFE_ACCOUNTING_PREVIEW_START jobId={} warehouseId={}",
                progress.jobId, progress.warehouseId
        );
        try {
            progress.accepted = false;
            progress.status = "PREPARING";
            publish(progress);

            try (SafeAccountingPriceGateway.PreviewSession session = gateway.open(
                    progress.warehouseId
            )) {
                SafeAccountingPriceGateway.PreviewScope scope = session.scope();
                progress.totalProducts = scope.skus().size();
                progress.status = "RUNNING";
                publish(progress);

                for (String sku : scope.skus()) {
                    progress.currentSku = sku;
                    SafeAccountingPriceGateway.SkuPreview result = session.previewOne(sku);
                    progress.processedProducts++;
                    if (result.problem() == null) {
                        progress.cleanProducts++;
                    } else {
                        progress.problemProducts++;
                        if ("NEGATIVE_CHRONOLOGICAL_STOCK".equals(
                                result.problem().code()
                        )) {
                            progress.negativeStockProducts++;
                        }
                        if (progress.problems.size() < MAX_REPORTED_PROBLEMS) {
                            progress.problems.add(result.problem());
                        } else {
                            progress.problemsTruncated = true;
                        }
                        log.warn(
                                "SAFE_ACCOUNTING_PREVIEW_PROBLEM jobId={} warehouseId={} sku={} "
                                        + "code={} recno={} formula={} denominator={}",
                                progress.jobId,
                                progress.warehouseId,
                                result.problem().sku(),
                                result.problem().code(),
                                result.problem().recno(),
                                result.problem().formula(),
                                result.problem().denominator()
                        );
                    }
                    if (result.returnCode() == 20) {
                        log.info(
                                "SAFE_ACCOUNTING_PREVIEW_SKIPPED jobId={} warehouseId={} sku={} "
                                        + "nextSku={}",
                                progress.jobId, progress.warehouseId, sku, result.nextSku()
                        );
                    }
                    if (progress.processedProducts % STATUS_UPDATE_INTERVAL == 0
                            || result.problem() != null) {
                        publish(progress);
                    }
                }
            }

            progress.running = false;
            progress.ok = true;
            progress.currentSku = null;
            progress.completedAt = now();
            progress.status = progress.problemProducts == 0
                    ? "COMPLETED"
                    : "COMPLETED_WITH_WARNINGS";
            log.info(
                    "SAFE_ACCOUNTING_PREVIEW_COMPLETE jobId={} warehouseId={} total={} "
                            + "clean={} problems={} negativeStock={} truncated={}",
                    progress.jobId,
                    progress.warehouseId,
                    progress.totalProducts,
                    progress.cleanProducts,
                    progress.problemProducts,
                    progress.negativeStockProducts,
                    progress.problemsTruncated
            );
        } catch (RuntimeException exception) {
            progress.ok = false;
            progress.running = false;
            progress.accepted = false;
            progress.status = "FAILED";
            progress.completedAt = now();
            progress.error = exception.getMessage() == null
                    ? "The safe Paint_Rus preview failed"
                    : exception.getMessage();
            log.error(
                    "SAFE_ACCOUNTING_PREVIEW_FAILED jobId={} warehouseId={} processed={} "
                            + "exceptionType={}",
                    progress.jobId,
                    progress.warehouseId,
                    progress.processedProducts,
                    exception.getClass().getName()
            );
        } finally {
            operationGate.release();
            if (!progress.running) {
                publish(progress);
            }
        }
    }

    private void publish(Progress progress) {
        Integer percent = progress.totalProducts == 0
                ? null
                : Math.min(
                        100,
                        (int) ((long) progress.processedProducts * 100L
                                / progress.totalProducts)
                );
        status.set(new SafeAccountingPricePreviewStatus(
                progress.ok,
                progress.accepted,
                progress.running,
                progress.jobId,
                progress.status,
                progress.warehouseId,
                progress.startedAt,
                progress.completedAt,
                progress.totalProducts,
                progress.processedProducts,
                progress.cleanProducts,
                progress.problemProducts,
                progress.negativeStockProducts,
                percent,
                progress.currentSku,
                progress.problemsTruncated,
                progress.problems,
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
        private final List<SafeAccountingPriceProblem> problems = new ArrayList<>();
        private boolean ok = true;
        private boolean accepted;
        private boolean running;
        private String status;
        private LocalDateTime completedAt;
        private int totalProducts;
        private int processedProducts;
        private int cleanProducts;
        private int problemProducts;
        private int negativeStockProducts;
        private String currentSku;
        private boolean problemsTruncated;
        private String error;

        private Progress(String jobId, int warehouseId, LocalDateTime startedAt) {
            this.jobId = jobId;
            this.warehouseId = warehouseId;
            this.startedAt = startedAt;
        }
    }
}
