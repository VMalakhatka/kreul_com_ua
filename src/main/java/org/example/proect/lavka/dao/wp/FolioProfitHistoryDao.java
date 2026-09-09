package org.example.proect.lavka.dao.wp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class FolioProfitHistoryDao {
    private final JdbcTemplate jdbc;
    public FolioProfitHistoryDao(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(transactionManager="wpTransactionManager")
    public Row reserve(String source, String month, String requestId, String hash, String requestJson) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("""
                    INSERT INTO folio_profit_report_revision
                    (source_database,report_month,request_id,request_hash,request_json,status,created_at)
                    VALUES (?,?,?,?,?,'RUNNING',UTC_TIMESTAMP(3))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1,source); statement.setString(2,month); statement.setString(3,requestId);
            statement.setString(4,hash); statement.setString(5,requestJson); return statement;
        },key);
        long id=java.util.Objects.requireNonNull(key.getKey()).longValue();
        jdbc.update("""
                INSERT INTO folio_profit_report_month(source_database,report_month,latest_revision_id)
                VALUES (?,?,?) ON DUPLICATE KEY UPDATE latest_revision_id=GREATEST(latest_revision_id,VALUES(latest_revision_id))
                """,source,month,id);
        return byId(source,month,id).orElseThrow();
    }

    @Transactional(transactionManager="wpTransactionManager")
    public void finish(String source, String month, long id, String status, String payload, boolean auditComplete, String errorCode) {
        if (!List.of("FAILED","COMPLETED","PROVISIONAL").contains(status)) throw new IllegalArgumentException("Invalid terminal status");
        int updated=jdbc.update("""
                UPDATE folio_profit_report_revision
                SET status=?,report_json=?,audit_complete=?,error_code=?,completed_at=UTC_TIMESTAMP(3)
                WHERE source_database=? AND report_month=? AND id=? AND status='RUNNING'
                """,status,payload,auditComplete,errorCode,source,month,id);
        if(updated!=1) throw new IllegalStateException("Profit revision already terminal or absent");
        if(!status.equals("FAILED")) jdbc.update("""
                UPDATE folio_profit_report_month SET published_revision_id=?
                WHERE source_database=? AND report_month=?
                  AND (published_revision_id IS NULL OR (?='COMPLETED' AND published_revision_id<?))
                """,id,source,month,status,id);
    }

    public Optional<Row> byRequest(String source,String requestId) {
        return jdbc.query("SELECT * FROM folio_profit_report_revision WHERE source_database=? AND request_id=?",MAPPER,source,requestId).stream().findFirst();
    }
    public Optional<Row> byId(String source,String month,long id) {
        return jdbc.query("SELECT * FROM folio_profit_report_revision WHERE source_database=? AND report_month=? AND id=?",MAPPER,source,month,id).stream().findFirst();
    }
    public Optional<State> state(String source,String month) {
        return jdbc.query("""
                SELECT s.published_revision_id,s.latest_revision_id,r.status AS latest_status
                FROM folio_profit_report_month s
                JOIN folio_profit_report_revision r ON r.id=s.latest_revision_id
                WHERE s.source_database=? AND s.report_month=?
                """,(rs,i)->new State(rs.getObject("published_revision_id")==null?null:rs.getLong("published_revision_id"),
                        rs.getLong("latest_revision_id"),rs.getString("latest_status")),source,month).stream().findFirst();
    }
    public List<Row> revisions(String source,String month,int limit,long beforeId) {
        return jdbc.query("""
                SELECT id,source_database,report_month,request_id,request_hash,request_json,status,
                       NULL AS report_json,audit_complete,error_code,created_at,completed_at
                FROM folio_profit_report_revision WHERE source_database=? AND report_month=? AND id<?
                ORDER BY id DESC LIMIT ?
                """,MAPPER,source,month,beforeId,limit);
    }
    private static final RowMapper<Row> MAPPER=(rs,i)->new Row(rs.getLong("id"),rs.getString("source_database"),rs.getString("report_month"),
            rs.getString("request_id"),rs.getString("request_hash"),rs.getString("request_json"),rs.getString("status"),rs.getString("report_json"),
            rs.getBoolean("audit_complete"),rs.getString("error_code"),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("completed_at")));
    private static Instant instant(Timestamp value) { return value==null?null:value.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC); }
    public record State(Long publishedRevisionId,long latestRevisionId,String latestStatus) {}
    public record Row(long id,String source,String month,String requestId,String requestHash,String requestJson,String status,
            String reportJson,boolean auditComplete,String errorCode,Instant createdAt,Instant completedAt) {}
}
