package org.example.proect.lavka.dao.wp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dto.sync.SyncChunkResult;
import org.example.proect.lavka.service.sync.FullSyncJob;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Repository
public class FullSyncJobStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public FullSyncJobStore(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc, ObjectMapper json,
                            @Qualifier("wpTransactionManager") PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.json = json; this.tx = new TransactionTemplate(manager);
    }
    public FullSyncJob get(String id) {
        var rows = jdbc.query("SELECT state_json FROM lavka_full_sync_job WHERE job_id=?",
                (rs, n) -> decode(rs.getString(1)), id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public FullSyncJob active() {
        var rows = jdbc.query("SELECT state_json FROM lavka_full_sync_job WHERE active_slot=1",
                (rs, n) -> decode(rs.getString(1)));
        return rows.isEmpty() ? null : rows.get(0);
    }
    /** Connection-scoped MariaDB lock: another JVM must not resume the same job concurrently. */
    public WorkerLease tryWorkerLease() {
        Connection connection = null;
        try {
            connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection();
            try (PreparedStatement query = connection.prepareStatement("SELECT GET_LOCK('lavka_full_sync_worker',0)")) {
                try (var rows = query.executeQuery()) {
                    if (rows.next() && rows.getInt(1) == 1) return new WorkerLease(connection);
                }
            }
            connection.close();
            return null;
        } catch (SQLException e) {
            // A timed-out GET_LOCK may have succeeded. Never return an uncertain named lock to a pool.
            if (connection != null) {
                try { connection.abort(Runnable::run); } catch (SQLException ignored) { }
                try { connection.close(); } catch (SQLException ignored) { }
            }
            throw new IllegalStateException("JOB_WORKER_LEASE_UNAVAILABLE");
        }
    }
    public static class WorkerLease implements AutoCloseable {
        private final Connection connection;
        WorkerLease(Connection connection) { this.connection = connection; }
        public void verify() {
            try (var query = connection.prepareStatement("SELECT IS_USED_LOCK('lavka_full_sync_worker') = CONNECTION_ID()")) {
                try (var rows = query.executeQuery()) {
                    if (rows.next() && rows.getInt(1) == 1) return;
                }
            } catch (SQLException ignored) { }
            throw new IllegalStateException("JOB_WORKER_LEASE_LOST");
        }
        @Override public void close() {
            try (var query = connection.prepareStatement("SELECT RELEASE_LOCK('lavka_full_sync_worker')")) {
                query.execute();
            } catch (SQLException ignored) {
                try { connection.abort(Runnable::run); } catch (SQLException suppressed) { }
            }
            finally { try { connection.close(); } catch (SQLException ignored) { } }
        }
    }
    public void insert(FullSyncJob job) {
        jdbc.update("INSERT INTO lavka_full_sync_job(job_id,active_slot,status,state_json) VALUES (?,1,?,?)",
                job.request.jobId(), job.status, encode(job));
    }
    public void save(FullSyncJob job) {
        job.updatedAt = System.currentTimeMillis();
        jdbc.update("UPDATE lavka_full_sync_job SET status=?, state_json=?, active_slot=?, updated_at=CURRENT_TIMESTAMP WHERE job_id=?",
                job.status, encode(job), job.safeToRelease ? null : 1, job.request.jobId());
    }
    public void checkpoint(FullSyncJob job, SyncChunkResult result) {
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO lavka_full_sync_chunk(job_id,chunk_no,result_json) VALUES (?,?,?)",
                    job.request.jobId(), job.chunks, encode(result));
            save(job);
        });
    }
    public List<Map<String, Object>> chunks(String id, int after) {
        return jdbc.query("SELECT chunk_no,result_json FROM lavka_full_sync_chunk WHERE job_id=? AND chunk_no>? ORDER BY chunk_no LIMIT 100",
                (rs, n) -> Map.of("chunk", rs.getInt(1), "result", readMap(rs.getString(2))), id, after);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("JOB_SERIALIZATION_FAILED"); }
    }
    private FullSyncJob decode(String value) {
        try { return json.readValue(value, FullSyncJob.class); }
        catch (Exception e) { throw new IllegalStateException("JOB_STATE_INVALID"); }
    }
    private Map<?, ?> readMap(String value) {
        try { return json.readValue(value, Map.class); }
        catch (Exception e) { throw new IllegalStateException("JOB_RESULT_INVALID"); }
    }
}
