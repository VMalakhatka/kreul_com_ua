package org.example.proect.lavka.dao.wp;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class S3MediaIndexDao {

    private final @Qualifier("wpJdbcTemplate")  JdbcTemplate jdbc;

    public S3MediaIndexDao(
            @Qualifier("wpJdbcTemplate") JdbcTemplate jdbc
    ) {
        this.jdbc = jdbc;
    }

    public record Row(
            String filenameLower,
            String fullKey,
            long sizeBytes,
            Instant lastModified,
            String etag
    ) {}

    public int[] upsertBatch(List<Row> rows) {
        final String sql = """
            INSERT INTO s3_media_index (filename_lower, full_key, size_bytes, last_modified, etag)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              size_bytes = VALUES(size_bytes),
              last_modified = VALUES(last_modified),
              etag = VALUES(etag),
              updated_at = CURRENT_TIMESTAMP
            """;

        return jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                Row r = rows.get(i);
                ps.setString(1, r.filenameLower());
                ps.setString(2, r.fullKey());
                ps.setLong(3, r.sizeBytes());
                ps.setTimestamp(4, r.lastModified() == null ? null : Timestamp.from(r.lastModified()));
                ps.setString(5, r.etag());
            }
            @Override public int getBatchSize() { return rows.size(); }
        });
    }

    public List<Row> findByFileName(String filenameLower) {
        return jdbc.query("""
            SELECT filename_lower, full_key, size_bytes, last_modified, etag
            FROM s3_media_index
            WHERE filename_lower = ?
            ORDER BY last_modified DESC, size_bytes DESC
            """,
                (rs, n) -> new Row(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getLong(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(),
                        rs.getString(5)
                ),
                filenameLower.toLowerCase()
        );
    }
}