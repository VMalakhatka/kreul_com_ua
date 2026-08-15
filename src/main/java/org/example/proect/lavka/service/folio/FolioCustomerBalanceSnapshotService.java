package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao.SnapshotClient;
import org.example.proect.lavka.dto.folio.FolioBalanceSnapshotStatusResponse;
import org.example.proect.lavka.dto.folio.FolioBalanceSnapshotStatusResponse.ActiveSnapshot;
import org.example.proect.lavka.dto.folio.FolioBalanceSnapshotStatusResponse.Building;
import org.example.proect.lavka.dto.folio.FolioCustomerBalanceResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class FolioCustomerBalanceSnapshotService {

    private static final int SNAPSHOT_WRITE_BATCH_SIZE = 200;
    private static final int LEASE_RENEW_EVERY_CLIENTS = 25;
    private static final String INTERRUPTED_MESSAGE =
            "Interrupted before completion; a clean recovery generation was started";
    private static final String RECOVERY_LIMIT_MESSAGE =
            "Interrupted before completion; automatic recovery limit reached";

    private final FolioCustomerBalanceDao balanceDao;
    private final FolioCustomerBalanceSnapshotDao snapshotDao;
    private final TaskExecutor executor;
    private final Clock clock;
    private final boolean scheduledEnabled;
    private final boolean recoveryEnabled;
    private final int leaseSeconds;
    private final int maxRecoveryAttemptsPerDay;
    private final AtomicBoolean localRefreshRunning = new AtomicBoolean(false);

    public FolioCustomerBalanceSnapshotService(
            FolioCustomerBalanceDao balanceDao,
            FolioCustomerBalanceSnapshotDao snapshotDao,
            @Qualifier("folioBalanceSnapshotExecutor") TaskExecutor executor,
            @Qualifier("folioBalanceClock") Clock clock,
            @Value("${lavka.folio.balance-snapshot.scheduled-enabled:true}") boolean scheduledEnabled,
            @Value("${lavka.folio.balance-snapshot.lease-seconds:7200}") int leaseSeconds,
            @Value("${lavka.folio.balance-snapshot.recovery-enabled:true}") boolean recoveryEnabled,
            @Value("${lavka.folio.balance-snapshot.max-recovery-attempts-per-day:2}")
            int maxRecoveryAttemptsPerDay) {
        this.balanceDao = balanceDao;
        this.snapshotDao = snapshotDao;
        this.executor = executor;
        this.clock = clock;
        this.scheduledEnabled = scheduledEnabled;
        this.leaseSeconds = Math.max(300, leaseSeconds);
        this.recoveryEnabled = recoveryEnabled;
        this.maxRecoveryAttemptsPerDay = Math.max(1, maxRecoveryAttemptsPerDay);
    }

    @Scheduled(
            cron = "${lavka.folio.balance-snapshot.cron:0 10 0 * * *}",
            zone = "${lavka.folio.balance-snapshot.zone:Europe/Kyiv}"
    )
    public void scheduledRefresh() {
        if (scheduledEnabled) {
            requestRefresh("SCHEDULED");
        }
    }

    @Scheduled(
            fixedDelayString = "${lavka.folio.balance-snapshot.recovery-check-ms:300000}",
            initialDelayString = "${lavka.folio.balance-snapshot.recovery-initial-delay-ms:30000}"
    )
    public void recoverInterruptedRefresh() {
        if (!recoveryEnabled || localRefreshRunning.get()) {
            return;
        }
        var latest = snapshotDao.findLatestGeneration();
        if (latest.isEmpty() || !"BUILDING".equals(latest.orElseThrow().status())) {
            return;
        }
        if (snapshotDao.isLeaseActive()) {
            return;
        }

        LocalDate today = LocalDate.now(clock);
        int attempts = snapshotDao.countRecoveryGenerations(today);
        if (attempts >= maxRecoveryAttemptsPerDay) {
            log.error("[folio.balance.snapshot] automatic recovery limit reached date={} attempts={}",
                    today, attempts);
            boolean markedFailed = snapshotDao.failAbandonedGenerationIfLeaseExpired(
                    latest.orElseThrow().generationId(),
                    RECOVERY_LIMIT_MESSAGE,
                    LocalDateTime.now(clock)
            );
            if (!markedFailed) {
                log.info("[folio.balance.snapshot] recovery-limit failure mark skipped: lease became active");
            }
            return;
        }
        log.warn("[folio.balance.snapshot] abandoned generation={} detected; scheduling recovery attempt={}",
                latest.orElseThrow().generationId(), attempts + 1);
        requestRefresh("RECOVERY");
    }

    public boolean requestRefresh(String triggerSource) {
        if (!localRefreshRunning.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> runFullRefresh(normalizeTrigger(triggerSource)));
            return true;
        } catch (RuntimeException e) {
            localRefreshRunning.set(false);
            throw e;
        }
    }

    public FolioBalanceSnapshotStatusResponse status(boolean refreshAccepted) {
        var latest = snapshotDao.findLatestGeneration();
        ActiveSnapshot activeSnapshot = snapshotDao.findActiveSnapshot()
                .map(active -> new ActiveSnapshot(
                        active.generationId(),
                        active.asOfDate(),
                        active.completedAt(),
                        active.totalClients()
                ))
                .orElse(null);
        if (latest.isEmpty()) {
            return new FolioBalanceSnapshotStatusResponse(
                    true, refreshAccepted, localRefreshRunning.get(), null,
                    "NOT_READY", null, null, null, null, 0, null,
                    null, activeSnapshot
            );
        }
        var generation = latest.orElseThrow();
        boolean leaseActive = "BUILDING".equals(generation.status()) && snapshotDao.isLeaseActive();
        boolean running = localRefreshRunning.get() || leaseActive;
        Building building = "BUILDING".equals(generation.status())
                ? new Building(
                        generation.generationId(),
                        generation.triggerSource(),
                        generation.asOfDate(),
                        generation.startedAt(),
                        generation.processedClients(),
                        generation.lastHeartbeatAt(),
                        leaseActive
                )
                : null;
        return new FolioBalanceSnapshotStatusResponse(
                true,
                refreshAccepted,
                running,
                generation.generationId(),
                generation.status(),
                generation.triggerSource(),
                generation.asOfDate(),
                generation.startedAt(),
                generation.completedAt(),
                generation.totalClients(),
                generation.errorMessage(),
                building,
                activeSnapshot
        );
    }

    public int updateActiveClient(LocalDate asOfDate,
                                  String partnerShortName,
                                  String partnerName,
                                  FolioCustomerBalanceResponse.Summary summary) {
        return snapshotDao.updateActiveClient(
                asOfDate,
                partnerShortName,
                partnerName,
                summary,
                LocalDateTime.now(clock)
        );
    }

    private void runFullRefresh(String triggerSource) {
        String ownerId = UUID.randomUUID().toString();
        Long generationId = null;
        try {
            if (!snapshotDao.tryAcquireLease(ownerId, leaseSeconds)) {
                log.info("[folio.balance.snapshot] refresh skipped: another instance owns the lease");
                return;
            }

            LocalDate asOfDate = LocalDate.now(clock);
            LocalDateTime startedAt = LocalDateTime.now(clock);
            var generationStart = snapshotDao.createGenerationReplacingAbandoned(
                    asOfDate, triggerSource, startedAt, INTERRUPTED_MESSAGE
            );
            generationId = generationStart.generationId();
            int abandoned = generationStart.abandonedGenerations();
            if (abandoned > 0) {
                log.warn("[folio.balance.snapshot] marked abandoned generations failed count={}", abandoned);
            }
            long buildingGenerationId = generationId;
            log.info("[folio.balance.snapshot] generation={} started asOfDate={} trigger={}",
                    generationId, asOfDate, triggerSource);

            List<SnapshotClient> buffer = new ArrayList<>(SNAPSHOT_WRITE_BATCH_SIZE);
            java.util.concurrent.atomic.AtomicInteger processedClients =
                    new java.util.concurrent.atomic.AtomicInteger();
            int totalClients = balanceDao.forEachPartnerBalance(
                    null,
                    List.of(),
                    FolioCustomerBalanceService.FOLIO_MIN_DATE,
                    asOfDate,
                    true,
                    result -> {
                var summary = FolioCustomerBalanceCalculator
                        .calculate(result.balance(), asOfDate, false)
                        .summary();
                var partner = result.partner();
                buffer.add(new SnapshotClient(
                        partner.shortName(),
                        partner.name(),
                        partner.type(),
                        partner.city(),
                        partner.phone(),
                        summary.commonDebt(),
                        summary.deferredAmount(),
                        summary.overdueDeferredAmount(),
                        summary.prepaymentAmount(),
                        summary.payableNow(),
                        LocalDateTime.now(clock)
                ));
                int processed = processedClients.incrementAndGet();
                if (buffer.size() >= SNAPSHOT_WRITE_BATCH_SIZE) {
                    snapshotDao.saveClients(buildingGenerationId, buffer);
                    buffer.clear();
                    snapshotDao.recordProgress(
                            buildingGenerationId, processed, LocalDateTime.now(clock)
                    );
                    requireLeaseRenewal(ownerId);
                } else if (processed % LEASE_RENEW_EVERY_CLIENTS == 0) {
                    snapshotDao.recordProgress(
                            buildingGenerationId, processed, LocalDateTime.now(clock)
                    );
                    requireLeaseRenewal(ownerId);
                }
            });

            snapshotDao.saveClients(buildingGenerationId, buffer);
            snapshotDao.recordProgress(
                    buildingGenerationId, processedClients.get(), LocalDateTime.now(clock)
            );
            requireLeaseRenewal(ownerId);
            if (totalClients <= 0) {
                throw new IllegalStateException("Balance snapshot contains no Folio partners");
            }
            snapshotDao.publishGeneration(buildingGenerationId, totalClients, LocalDateTime.now(clock));
            log.info("[folio.balance.snapshot] generation={} published clients={}",
                    generationId, totalClients);
        } catch (Exception e) {
            log.error("[folio.balance.snapshot] generation={} failed: {}",
                    generationId, e.getMessage(), e);
            if (generationId != null) {
                try {
                    snapshotDao.failGeneration(generationId, e.getMessage(), LocalDateTime.now(clock));
                } catch (Exception markFailedError) {
                    log.error("[folio.balance.snapshot] cannot mark generation={} as failed",
                            generationId, markFailedError);
                }
            }
        } finally {
            try {
                snapshotDao.releaseLease(ownerId);
            } catch (Exception releaseError) {
                log.warn("[folio.balance.snapshot] cannot release lease owner={}: {}",
                        ownerId, releaseError.getMessage());
            }
            localRefreshRunning.set(false);
        }
    }

    private static String normalizeTrigger(String triggerSource) {
        String value = triggerSource == null ? "MANUAL" : triggerSource.trim().toUpperCase();
        if (value.isEmpty()) {
            return "MANUAL";
        }
        return value.length() <= 20 ? value : value.substring(0, 20);
    }

    private void requireLeaseRenewal(String ownerId) {
        if (!snapshotDao.renewLease(ownerId, leaseSeconds)) {
            throw new IllegalStateException("Balance snapshot lease was lost during generation");
        }
    }
}
