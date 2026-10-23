package org.example.proect.lavka.dao.wp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dto.sync.SyncChunkResult;
import org.example.proect.lavka.service.sync.FullSyncJob;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Only a disposable loopback database with this fixed test name. Never loads application configuration. */
@EnabledIfSystemProperty(named="fullsync.test.mariadb.port", matches="[0-9]+")
class FullSyncJobMariaDbTest {
    JdbcTemplate jdbc; FullSyncJobStore store;
    @BeforeEach void setup() {
        int port=Integer.parseInt(System.getProperty("fullsync.test.mariadb.port"));
        if (port<1024 || port>65535) throw new IllegalArgumentException("Disposable port required");
        var ds=new DriverManagerDataSource("jdbc:mariadb://127.0.0.1:"+port+"/full_sync_job_test","root","");
        jdbc=new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("full_sync_job_test");
        jdbc.execute("DROP TABLE IF EXISTS lavka_full_sync_chunk"); jdbc.execute("DROP TABLE IF EXISTS lavka_full_sync_job");
        new ResourceDatabasePopulator(new ClassPathResource("db/wp/migration/V19__full_sync_jobs.sql")).execute(ds);
        store=new FullSyncJobStore(jdbc,new ObjectMapper(),new DataSourceTransactionManager(ds));
    }
    FullSyncJob job() {
        return new FullSyncJob(new FullSyncJob.Request(UUID.randomUUID().toString(),UUID.randomUUID().toString(),900000,50,"!",false,false));
    }
    @Test void stateSurvivesStoreRecreationAndActiveSlotIsUnique() {
        var job=job(); store.insert(job);
        assertThat(store.get(job.request.jobId()).request).isEqualTo(job.request);
        assertThatThrownBy(() -> store.insert(job())).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        job.status="COMPLETED"; job.safeToRelease=true; store.save(job);
        assertThat(store.active()).isNull(); store.insert(job());
    }
    @Test void failedCheckpointRollsBackBothResultAndCursor() {
        var job=job(); job.inFlight=true; store.insert(job);
        jdbc.execute("ALTER TABLE lavka_full_sync_job ADD CONSTRAINT test_no_completed CHECK (status <> 'COMPLETED')");
        job.chunks=1; job.cursor="Z"; job.inFlight=false; job.status="COMPLETED"; job.safeToRelease=true;
        assertThatThrownBy(() -> store.checkpoint(job,new SyncChunkResult(50,0,50,0,"Z",true,List.of())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.get(job.request.jobId()).cursor).isEqualTo("!");
        assertThat(store.get(job.request.jobId()).inFlight).isTrue();
        assertThat(store.chunks(job.request.jobId(),0)).isEmpty();
    }
    @Test void checkpointAndHistoryAreAtomicAndRetainedAfterNewJob() {
        var job=job(); store.insert(job); job.chunks=1; job.cursor="A"; job.status="COMPLETED_WITH_ERRORS"; job.safeToRelease=true;
        store.checkpoint(job,new SyncChunkResult(1,0,0,0,"A",true,List.of("SYNTHETIC_ITEM_ERROR")));
        store.insert(job());
        assertThat(store.get(job.request.jobId()).cursor).isEqualTo("A");
        assertThat(store.chunks(job.request.jobId(),0)).hasSize(1);
        assertThat(store.chunks(job.request.jobId(),1)).isEmpty();
    }
    @Test void secondConnectionCannotOwnWorkerUntilFirstReleasesIt() {
        try(var lease=store.tryWorkerLease()) {
            assertThat(lease).isNotNull(); lease.verify();
            assertThat(store.tryWorkerLease()).isNull();
        }
        try(var lease=store.tryWorkerLease()) { assertThat(lease).isNotNull(); lease.verify(); }
    }
}
