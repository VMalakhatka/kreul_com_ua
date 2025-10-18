package org.example.proect.lavka.dao.category;

import org.example.proect.lavka.entity.category.LavkaCatmap;
import org.example.proect.lavka.utils.RetryLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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

    // ВАЖНО: alias 'newv' вместо VALUES()
    private static final String SQL_UPSERT = """
        INSERT INTO wp_lavka_catmap
            (path_text, depth, parent_path_hash,
             l1,l2,l3,l4,l5,l6,
             wc_parent_id, wc_term_id, slug,
             l1_norm,l2_norm,l3_norm,l4_norm,l5_norm,l6_norm)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) AS newv
        ON DUPLICATE KEY UPDATE
             depth            = newv.depth,
             parent_path_hash = newv.parent_path_hash,
             l1 = newv.l1, l2 = newv.l2, l3 = newv.l3,
             l4 = newv.l4, l5 = newv.l5, l6 = newv.l6,
             wc_parent_id     = newv.wc_parent_id,
             wc_term_id       = newv.wc_term_id,
             slug             = newv.slug,
             l1_norm = newv.l1_norm, l2_norm = newv.l2_norm, l3_norm = newv.l3_norm,
             l4_norm = newv.l4_norm, l5_norm = newv.l5_norm, l6_norm = newv.l6_norm
        """;

    // ---------- mapper ----------
    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<LavkaCatmap> M = (rs, i) -> {
        LavkaCatmap x = new LavkaCatmap();
        x.setId(rs.getLong("id"));
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
            log.error("findByPathHash failed for hash={}: {}", pathHash,
                    e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage(), e);
            throw e;
        }
    }

    /** ТОЛЬКО привязка к Woo — не трогаем generated-колонки */
    public int updateWooBinding(String pathHash, Long wcParentId, Long wcTermId, String slug) {
        // передаём типы, чтобы убрать DEBUG про getParameterType
        Object[] args = { wcParentId, wcTermId, slug, pathHash };
        int[]    tps  = { Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.VARCHAR };
        return jdbc.update(SQL_UPDATE_WOO_BINDING, args, tps);
    }

    /** UPSERT: не пишем path_hash/parent_path_hash — их посчитает БД/триггер */
    public void upsert(LavkaCatmap x) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Catmap UPSERT start: path='{}', depth={}, parentHash={}, wcParent={}, wcTerm={}, slug='{}'",
                        x.getPathText(), x.getDepth(), x.getParentPathHash(), x.getWcParentId(), x.getWcTermId(), x.getSlug());
            }

            Object[] args = {
                    x.getPathText(), x.getDepth(), x.getParentPathHash(),
                    x.getL1(), x.getL2(), x.getL3(), x.getL4(), x.getL5(), x.getL6(),
                    x.getWcParentId(), x.getWcTermId(), x.getSlug(),
                    x.getL1Norm(), x.getL2Norm(), x.getL3Norm(), x.getL4Norm(), x.getL5Norm(), x.getL6Norm()
            };
            int[] types = {
                    Types.VARCHAR, Types.INTEGER, Types.VARCHAR,
                    Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                    Types.BIGINT, Types.BIGINT, Types.VARCHAR,
                    Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR
            };

            jdbc.update(SQL_UPSERT, args, types);

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