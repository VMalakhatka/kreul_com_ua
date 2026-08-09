package org.example.proect.lavka.dao.wp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class FolioProductMediaRequestDao {

    private final JdbcTemplate jdbc;

    public FolioProductMediaRequestDao(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredRequest(
            String externalRequestId,
            String requestHash,
            String responseJson
    ) {
    }

    public @Nullable StoredRequest find(String externalRequestId) {
        List<StoredRequest> rows = jdbc.query("""
                SELECT external_request_id, request_hash, response_json
                  FROM folio_product_media_requests
                 WHERE external_request_id = ?
                """, (rs, rowNum) -> new StoredRequest(
                rs.getString("external_request_id"),
                rs.getString("request_hash"),
                rs.getString("response_json")
        ), externalRequestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void save(String externalRequestId, String requestHash, String responseJson) {
        jdbc.update("""
                INSERT INTO folio_product_media_requests
                       (external_request_id, request_hash, response_json)
                VALUES (?, ?, ?)
                """, externalRequestId, requestHash, responseJson);
    }
}
