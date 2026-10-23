package org.example.proect.lavka.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.client.WooApiClient;
import org.example.proect.lavka.dao.wp.FullSyncJobStore;
import org.example.proect.lavka.dto.sync.SyncChunkResult;
import org.example.proect.lavka.service.SyncService;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FullSyncJobServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final Map<String, FullSyncJob> persisted = new HashMap<>();
    final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    FullSyncJobStore store; SyncService sync; WooApiClient woo; FullSyncJobService jobs;
    FullSyncJob.Request request;
    FullSyncJob copy(FullSyncJob job) { return json.convertValue(job, FullSyncJob.class); }
    @BeforeEach void setup() {
        store = mock(FullSyncJobStore.class); sync = mock(SyncService.class); woo = mock(WooApiClient.class);
        request = new FullSyncJob.Request(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 900000, 50, "!", false, false);
        when(store.get(anyString())).thenAnswer(i -> persisted.containsKey(i.getArgument(0)) ? copy(persisted.get(i.getArgument(0))) : null);
        when(store.active()).thenAnswer(i -> persisted.values().stream().filter(j -> !j.safeToRelease).findFirst().map(this::copy).orElse(null));
        doAnswer(i -> { FullSyncJob j=i.getArgument(0); persisted.put(j.request.jobId(),copy(j)); return null; }).when(store).insert(any());
        doAnswer(i -> { FullSyncJob j=i.getArgument(0); persisted.put(j.request.jobId(),copy(j)); return null; }).when(store).save(any());
        doAnswer(i -> { store.save(i.getArgument(0)); return null; }).when(store).checkpoint(any(),any());
        when(store.tryWorkerLease()).thenReturn(mock(FullSyncJobStore.WorkerLease.class));
        when(woo.ownsFullSyncLease(anyString(),anyString())).thenReturn(true);
        jobs = new FullSyncJobService(store,sync,woo,queue::add);
    }
    SyncChunkResult chunk(String next, boolean end, String... errors) {
        return new SyncChunkResult(1,0,1,0,next,end,List.of(errors));
    }
    FullSyncJob saved() { return persisted.get(request.jobId()); }

    @Test void startReturnsBeforeWorkAndDuplicateIdNeverCreatesAnotherWorker() {
        assertThat(jobs.start(request).status).isEqualTo("QUEUED");
        jobs.start(request); jobs.status(request.jobId());
        assertThat(queue).hasSize(1); verifyNoInteractions(sync,woo);
        verify(store,times(1)).insert(any());
    }
    @Test void checkpointThenKnownItemErrorContinuesToEnd() {
        when(sync.runDurableChunk(50,"!",false,false)).thenReturn(chunk("A",false,"SKU_A_REJECTED"));
        when(sync.runDurableChunk(50,"A",false,false)).thenReturn(chunk("B",true));
        jobs.start(request); queue.remove().run();
        assertThat(saved().status).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(saved().safeToRelease).isTrue(); assertThat(saved().chunks).isEqualTo(2);
        assertThat(saved().cursor).isEqualTo("B"); assertThat(saved().errors).containsExactly("SKU_A_REJECTED");
        verify(store,times(2)).checkpoint(any(),any());
        var next = new FullSyncJob.Request(UUID.randomUUID().toString(),UUID.randomUUID().toString(),900000,50,"!",false,false);
        assertThat(jobs.start(next).status).isEqualTo("QUEUED");
    }
    @Test void lostPostResponseCanBeReconciledUsingSameIdEvenAfterCompletion() {
        when(sync.runDurableChunk(anyInt(),any(),anyBoolean(),anyBoolean())).thenReturn(chunk("A",true));
        jobs.start(request); queue.remove().run();
        assertThat(jobs.start(request).status).isEqualTo("COMPLETED");
        assertThat(queue).isEmpty(); verify(sync,times(1)).runDurableChunk(anyInt(),any(),anyBoolean(),anyBoolean());
        assertThat(saved().view()).doesNotContainKey("lockToken");
    }
    @Test void changedPayloadAndSecondJobAreRejected() {
        jobs.start(request);
        assertThatThrownBy(() -> jobs.start(new FullSyncJob.Request(request.jobId(),request.lockToken(),1,1,"!",false,false)))
                .hasMessage("JOB_REQUEST_CONFLICT");
        assertThatThrownBy(() -> jobs.start(new FullSyncJob.Request(UUID.randomUUID().toString(),request.lockToken(),1,1,"!",false,false)))
                .hasMessage("FULL_SYNC_ALREADY_ACTIVE");
    }
    @Test void transportFailureDuringWriteHoldsLockAndNeverReplays() {
        when(sync.runDurableChunk(anyInt(),any(),anyBoolean(),anyBoolean())).thenThrow(new SyncOutcomeUnknownException());
        jobs.start(request); queue.remove().run(); jobs.status(request.jobId()); jobs.start(request);
        assertThat(saved().status).isEqualTo("OUTCOME_UNKNOWN"); assertThat(saved().safeToRelease).isFalse();
        assertThat(saved().cursor).isEqualTo("!"); assertThat(queue).isEmpty();
        verify(sync,times(1)).runDurableChunk(anyInt(),any(),anyBoolean(),anyBoolean());
    }
    @Test void preparationFailureIsConfirmedTerminalAndNextScheduleCanStart() {
        when(sync.runDurableChunk(anyInt(),any(),anyBoolean(),anyBoolean())).thenThrow(new IllegalStateException("read failed"));
        jobs.start(request); queue.remove().run();
        assertThat(saved().status).isEqualTo("COMPLETED_WITH_ERRORS"); assertThat(saved().safeToRelease).isTrue();
        assertThat(saved().inFlight).isFalse();
    }
    @Test void failedCheckpointInSameJvmCannotRepeatSuccessfulWrite() {
        when(sync.runDurableChunk(anyInt(),any(),anyBoolean(),anyBoolean())).thenReturn(chunk("A",true));
        doThrow(new IllegalStateException("db lost")).when(store).checkpoint(any(),any());
        jobs.start(request);
        assertThatThrownBy(() -> queue.remove().run()).hasMessage("db lost");
        assertThat(saved().inFlight).isTrue();
        jobs.status(request.jobId()); queue.remove().run();
        assertThat(saved().status).isEqualTo("OUTCOME_UNKNOWN"); assertThat(saved().safeToRelease).isFalse();
        verify(sync,times(1)).runDurableChunk(anyInt(),any(),anyBoolean(),anyBoolean());
    }
    @Test void restartResumesOnlySavedBoundaryAndNeverInFlightChunk() {
        var job = new FullSyncJob(request); job.cursor="A"; job.chunks=1; store.insert(job);
        when(sync.runDurableChunk(50,"A",false,false)).thenReturn(chunk("B",true));
        jobs.recover(); queue.remove().run(); assertThat(saved().status).isEqualTo("COMPLETED");
        job.inFlight=true; job.instance="old process"; store.save(job);
        jobs.recover(); queue.remove().run();
        assertThat(saved().status).isEqualTo("OUTCOME_UNKNOWN");
        verify(sync,times(1)).runDurableChunk(anyInt(),any(),anyBoolean(),anyBoolean());
    }
    @Test void leaseUnavailableStopsAtBoundaryAndLaterPollResumesSameCursor() {
        jobs.start(request);
        when(woo.ownsFullSyncLease(anyString(),anyString())).thenReturn(false);
        queue.remove().run(); verifyNoInteractions(sync); assertThat(saved().inFlight).isFalse();
        when(woo.ownsFullSyncLease(anyString(),anyString())).thenReturn(true);
        when(sync.runDurableChunk(anyInt(),any(),anyBoolean(),anyBoolean())).thenReturn(chunk("A",true));
        jobs.status(request.jobId()); queue.remove().run(); assertThat(saved().status).isEqualTo("COMPLETED");
    }
    @Test void anotherJvmOwnsWorkerLeaseDoesNotAlterInFlightState() {
        var job=new FullSyncJob(request); job.inFlight=true; job.status="RUNNING"; store.insert(job);
        when(store.tryWorkerLease()).thenReturn(null);
        jobs.recover(); queue.remove().run();
        assertThat(saved().status).isEqualTo("RUNNING"); verifyNoInteractions(sync);
    }
    @Test void limitIsExplicitlyPausedNotReportedAsCompletedCatalogue() {
        request=new FullSyncJob.Request(request.jobId(),request.lockToken(),1,50,"!",true,true);
        when(sync.runDurableChunk(1,"!",true,true)).thenReturn(chunk("A",false));
        jobs.start(request); queue.remove().run();
        assertThat(saved().status).isEqualTo("PAUSED_LIMIT"); assertThat(saved().exhausted).isFalse();
        assertThat(saved().safeToRelease).isTrue();
    }
    @Test void stationaryCursorFailsInsteadOfLoopingForever() {
        when(sync.runDurableChunk(anyInt(),any(),anyBoolean(),anyBoolean())).thenReturn(chunk("!",false));
        jobs.start(request); queue.remove().run();
        assertThat(saved().error).isEqualTo("CURSOR_DID_NOT_ADVANCE"); assertThat(saved().safeToRelease).isTrue();
    }
}
