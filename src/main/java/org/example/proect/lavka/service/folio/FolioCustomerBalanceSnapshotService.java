package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao.SnapshotClient;
import org.example.proect.lavka.dto.folio.FolioBalanceSnapshotStatusResponse;
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

    private final FolioCustomerBalanceDao balanceDao;
    private final FolioCustomerBalanceSnapshotDao snapshotDao;
    private final TaskExecutor executor;
    private final Clock clock;
    private final boolean scheduledEnabled;
    private final int leaseSeconds;
    private final AtomicBoolean localRefreshRunning = new AtomicBoolean(false);

    public FolioCustomerBalanceSnapshotService(
            FolioCustomerBalanceDao balanceDao,
            FolioCustomerBalanceSnapshotDao snapshotDao,
            @Qualifier("folioBalanceSnapshotExecutor") TaskExecutor executor,
            @Qualifier("folioBalanceClock") Clock clock,
            @Value("${lavka.folio.balance-snapshot.scheduled-enabled:true}") boolean scheduledEnabled,
            @Value("${lavka.folio.balance-snapshot.lease-seconds:7200}") int leaseSeconds) {
        this.balanceDao = balanceDao;
        this.snapshotDao = snapshotDao;
        this.executor = executor;
        this.clock = clock;
        this.scheduledEnabled = scheduledEnabled;
        this.leaseSeconds = Math.max(300, leaseSeconds);
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
        if (latest.isEmpty()) {
            return new FolioBalanceSnapshotStatusResponse(
                    true, refreshAccepted, localRefreshRunning.get(), null,
                    "NOT_READY", null, null, null, null, 0, null
            );
        }
        var generation = latest.orElseThrow();
        boolean running = localRefreshRunning.get() || "BUILDING".equals(generation.status());
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
                generation.errorMessage()
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
            generationId = snapshotDao.createGeneration(asOfDate, triggerSource, startedAt);
            long buildingGenerationId = generationId;
            log.info("[folio.balance.snapshot] generation={} started asOfDate={} trigger={}",
                    generationId, asOfDate, triggerSource);

            List<SnapshotClient> buffer = new ArrayList<>(SNAPSHOT_WRITE_BATCH_SIZE);
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
                if (buffer.size() >= SNAPSHOT_WRITE_BATCH_SIZE) {
                    snapshotDao.saveClients(buildingGenerationId, buffer);
                    buffer.clear();
                    requireLeaseRenewal(ownerId);
                }
            });

            snapshotDao.saveClients(buildingGenerationId, buffer);
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
