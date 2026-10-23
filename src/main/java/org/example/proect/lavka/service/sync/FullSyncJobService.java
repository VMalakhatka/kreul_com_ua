package org.example.proect.lavka.service.sync;

import jakarta.annotation.PreDestroy;
import org.example.proect.lavka.client.WooApiClient;
import org.example.proect.lavka.dao.wp.FullSyncJobStore;
import org.example.proect.lavka.service.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Service
@lombok.extern.slf4j.Slf4j
public class FullSyncJobService {
    private final FullSyncJobStore store;
    private final SyncService sync;
    private final WooApiClient woo;
    private final Executor executor;
    private final Set<String> workers = ConcurrentHashMap.newKeySet();
    private final String instance = UUID.randomUUID().toString();

    @Autowired
    public FullSyncJobService(FullSyncJobStore store, SyncService sync, WooApiClient woo) {
        this(store, sync, woo, Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "full-sync-job"); thread.setDaemon(true); return thread;
        }));
    }
    public FullSyncJobService(FullSyncJobStore store, SyncService sync, WooApiClient woo, Executor executor) {
        this.store = store; this.sync = sync; this.woo = woo; this.executor = executor;
    }
    public synchronized FullSyncJob start(FullSyncJob.Request request) {
        request.validate();
        FullSyncJob existing = store.get(request.jobId());
        if (existing != null) {
            if (!existing.request.equals(request)) throw new IllegalArgumentException("JOB_REQUEST_CONFLICT");
            wake(existing); return store.get(request.jobId());
        }
        if (store.active() != null) throw new IllegalStateException("FULL_SYNC_ALREADY_ACTIVE");
        // Acceptance only persists intent. The worker checks the WordPress lease before any writes.
        FullSyncJob job = new FullSyncJob(request);
        try { store.insert(job); }
        catch (DuplicateKeyException e) {
            existing = store.get(request.jobId());
            if (existing == null) throw new IllegalStateException("FULL_SYNC_ALREADY_ACTIVE");
            if (!existing.request.equals(request)) throw new IllegalArgumentException("JOB_REQUEST_CONFLICT");
            job = existing;
        }
        wake(job);
        return store.get(request.jobId());
    }
    public synchronized FullSyncJob status(String id) {
        FullSyncJob job = store.get(id);
        if (job != null) wake(job);
        return store.get(id);
    }
    public void assertNoActive() {
        if (store.active() != null) throw new IllegalStateException("FULL_SYNC_ALREADY_ACTIVE");
    }
    public <T> T legacy(java.util.function.Supplier<T> operation) {
        try (var lease = store.tryWorkerLease()) {
            if (lease == null) throw new IllegalStateException("FULL_SYNC_ALREADY_ACTIVE");
            assertNoActive();
            lease.verify();
            return operation.get();
        }
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void recover() {
        FullSyncJob job = store.active();
        if (job != null) wake(job);
    }
    private void wake(FullSyncJob job) {
        if (job.safeToRelease || !List.of("QUEUED", "RUNNING").contains(job.status) || !workers.add(job.request.jobId())) return;
        try { executor.execute(() -> work(job.request.jobId())); }
        catch (RejectedExecutionException e) { workers.remove(job.request.jobId()); }
    }
    private void work(String id) {
        try (var context = org.slf4j.MDC.putCloseable("reqId", id); var lease = store.tryWorkerLease()) {
            if (lease == null) return;
            FullSyncJob interrupted = store.get(id);
            if (interrupted != null && interrupted.inFlight) {
                interrupted.status = "OUTCOME_UNKNOWN";
                interrupted.error = "CHUNK_CHECKPOINT_MISSING";
                log.warn("Full sync {} requires review: missing chunk checkpoint", id);
                interrupted.safeToRelease = false; store.save(interrupted); return;
            }
            while (true) {
                FullSyncJob job = store.get(id);
                if (job == null || job.safeToRelease || job.status.equals("OUTCOME_UNKNOWN")) return;
                try {
                    if (!woo.ownsFullSyncLease(id, job.request.lockToken())) {
                        job.error = "ECOSYSTEM_LEASE_MISSING"; store.save(job); return;
                    }
                } catch (RuntimeException e) {
                    job.error = "LEASE_CHECK_UNAVAILABLE"; store.save(job); return;
                } // A later GET resumes this safe boundary.
                job.error = null; job.status = "RUNNING"; job.instance = instance; job.inFlight = true; store.save(job);
                lease.verify();
                org.example.proect.lavka.dto.sync.SyncChunkResult result;
                try {
                    int size = Math.min(job.request.pageSizeWoo(), Math.max(1, job.request.limit() - job.processed));
                    result = sync.runDurableChunk(size, job.cursor, job.request.dryRun(), job.request.forceRefresh());
                } catch (SyncOutcomeUnknownException e) {
                    log.warn("Full sync {} requires review: {}", id, e.getMessage());
                    job.status = "OUTCOME_UNKNOWN"; job.error = e.getMessage(); store.save(job); return;
                } catch (RuntimeException e) {
                    log.warn("Full sync {} preparation failed: {}", id, e.getClass().getSimpleName());
                    job.status = "COMPLETED_WITH_ERRORS"; job.error = "CHUNK_READ_OR_PREPARATION_FAILED";
                    job.errorCount++; job.inFlight = false; job.safeToRelease = true; store.save(job); return;
                }
                if (!result.exhausted() && (result.nextAfter() == null || Objects.equals(result.nextAfter(), job.cursor))) {
                    job.status = "COMPLETED_WITH_ERRORS"; job.error = "CURSOR_DID_NOT_ADVANCE";
                    job.errorCount++; job.safeToRelease = true;
                }
                job.cursor = result.nextAfter(); job.processed += result.processed(); job.created += result.created();
                job.updated += result.updated(); job.drafted += result.drafted(); job.chunks++;
                job.errorCount += result.errors().size();
                for (String error : result.errors()) if (job.errors.size() < 50) job.errors.add(error);
                job.exhausted = result.exhausted(); job.inFlight = false;
                if (!job.safeToRelease) {
                    if (result.exhausted()) {
                        job.status = job.errorCount == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS"; job.safeToRelease = true;
                    } else if (job.processed >= job.request.limit()) {
                        job.status = "PAUSED_LIMIT"; job.safeToRelease = true;
                    } else job.status = "QUEUED";
                }
                // If this transaction fails, the stored in-flight marker remains. Do not replay the chunk.
                store.checkpoint(job, result);
                log.info("Full sync {} checkpoint {}: cursor={}, processed={}, errors={}, status={}",
                        id, job.chunks, job.cursor, job.processed, job.errorCount, job.status);
                if (job.safeToRelease) return;
            }
        } finally { workers.remove(id); }
    }
    @PreDestroy
    public void close() { if (executor instanceof ExecutorService service) service.shutdown(); }
}
