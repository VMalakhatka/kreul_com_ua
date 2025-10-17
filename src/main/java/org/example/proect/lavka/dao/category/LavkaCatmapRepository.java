package org.example.proect.lavka.dao.category;

import org.example.proect.lavka.entity.category.LavkaCatmap;
import org.example.proect.lavka.utils.RetryLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@RetryLabel("LavkaCatmapRepository")
@Repository
@Retryable(
        include = {
                org.springframework.dao.DeadlockLoserDataAccessException.class,
                org.springframework.dao.CannotAcquireLockException.class,
                org.springframework.dao.QueryTimeoutException.class,
                org.springframework.dao.TransientDataAccessResourceException.class
        },
        maxAttempts = 4,
        backoff = @Backoff(delay = 200, multiplier = 2.0, maxDelay = 5000, random = true)
)
public class LavkaCatmapRepository {

    private static final Logger log = LoggerFactory.getLogger(LavkaCatmapRepository.class);

    private final JdbcTemplate jdbc;

    public LavkaCatmapRepository(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- SQL ----------
    private static final String SQL_SELECT_BY_HASH = """
        SELECT *
          FROM wp_lavka_catmap
         WHERE path_hash = ?
        """;

    private static final String SQL_UPDATE_WOO_BINDING = """
        UPDATE wp_lavka_catmap
           SET wc_parent_id = ?, wc_term_id = ?, slug = ?, updated_at = CURRENT_TIMESTAMP
         WHERE path_hash = ?
        """;

    private static final String SQL_UPSERT = """
        INSERT INTO wp_lavka_catmap
            (path_text, depth, parent_path_hash,
             l1,l2,l3,l4,l5,l6,
             wc_parent_id, wc_term_id, slug,
             l1_norm,l2_norm,l3_norm,l4_norm,l5_norm,l6_norm)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE
             depth=VALUES(depth),
             parent_path_hash=VALUES(parent_path_hash),
             l1=VALUES(l1), l2=VALUES(l2), l3=VALUES(l3),
             l4=VALUES(l4), l5=VALUES(l5), l6=VALUES(l6),
             wc_parent_id=VALUES(wc_parent_id),
             wc_term_id=VALUES(wc_term_id),
             slug=VALUES(slug),
             l1_norm=VALUES(l1_norm), l2_norm=VALUES(l2_norm), l3_norm=VALUES(l3_norm),
             l4_norm=VALUES(l4_norm), l5_norm=VALUES(l5_norm), l6_norm=VALUES(l6_norm)
        """;

    // ---------- mapper ----------
    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<LavkaCatmap> M = (rs, i) -> {
        LavkaCatmap x = new LavkaCatmap();
        x.setId(rs.getLong("id")); // ok; если NULL не бывает
        x.setPathText(rs.getString("path_text"));
        x.setPathHash(rs.getString("path_hash"));
        x.setL1(rs.getString("l1"));
        x.setL2(rs.getString("l2"));
        x.setL3(rs.getString("l3"));
        x.setL4(rs.getString("l4"));
        x.setL5(rs.getString("l5"));
        x.setL6(rs.getString("l6"));
        x.setDepth(rs.getInt("depth"));
        x.setParentPathHash(rs.getString("parent_path_hash"));

        // ВАЖНО: без кастов к Long
        x.setWcParentId(getNullableLong(rs, "wc_parent_id"));
        x.setWcTermId(getNullableLong(rs, "wc_term_id"));

        x.setSlug(rs.getString("slug"));
        x.setL1Norm(rs.getString("l1_norm"));
        x.setL2Norm(rs.getString("l2_norm"));
        x.setL3Norm(rs.getString("l3_norm"));
        x.setL4Norm(rs.getString("l4_norm"));
        x.setL5Norm(rs.getString("l5_norm"));
        x.setL6Norm(rs.getString("l6_norm"));

        var cts = rs.getTimestamp("created_at");
        var uts = rs.getTimestamp("updated_at");
        x.setCreatedAt(cts != null ? cts.toLocalDateTime() : null);
        x.setUpdatedAt(uts != null ? uts.toLocalDateTime() : null);
        return x;
    };

    // ---------- queries ----------
    public Optional<LavkaCatmap> findByPathHash(String pathHash) {
        try {
            var list = jdbc.query(SQL_SELECT_BY_HASH, M, pathHash);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        } catch (DataAccessException e) {
            // Коротко фиксируем ошибку (в upsert у нас подробный лог)
            log.error("findByPathHash failed for hash={}: {}", pathHash, e.getMostSpecificCause() != null
                    ? e.getMostSpecificCause().getMessage() : e.getMessage(), e);
            throw e;
        }
    }

    /** ТОЛЬКО привязка к Woo — не трогаем generated-колонки */
    public int updateWooBinding(String pathHash, Long wcParentId, Long wcTermId, String slug) {
        return jdbc.update(SQL_UPDATE_WOO_BINDING, wcParentId, wcTermId, slug, pathHash);
    }

    /** UPSERT: не пишем path_hash/parent_path_hash — их посчитает БД/триггер */
    public void upsert(LavkaCatmap x) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Catmap UPSERT start: path='{}', depth={}, parentHash={}, wcParent={}, wcTerm={}, slug='{}'",
                        x.getPathText(), x.getDepth(), x.getParentPathHash(), x.getWcParentId(), x.getWcTermId(), x.getSlug());
            }

            jdbc.update(SQL_UPSERT,
                    x.getPathText(), x.getDepth(), x.getParentPathHash(),
                    x.getL1(), x.getL2(), x.getL3(), x.getL4(), x.getL5(), x.getL6(),
                    x.getWcParentId(), x.getWcTermId(), x.getSlug(),
                    x.getL1Norm(), x.getL2Norm(), x.getL3Norm(), x.getL4Norm(), x.getL5Norm(), x.getL6Norm()
            );

            if (log.isDebugEnabled()) {
                log.debug("Catmap UPSERT ok: path='{}'", x.getPathText());
            }
        } catch (DataAccessException e) {
            var rootMsg = (e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage());
            log.error("Catmap UPSERT failed: path='{}', depth={}, wcTermId={}, error={}",
                    x.getPathText(), x.getDepth(), x.getWcTermId(), rootMsg, e);
            throw e;
        }
    }
}