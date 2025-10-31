package org.example.proect.lavka.dao.wp;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.service.CardTovExportService;
import org.example.proect.lavka.service.CardTovExportService.ItemHash;
import org.example.proect.lavka.utils.RetryLabel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@RetryLabel("WpProductDaoImpl")
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
@RequiredArgsConstructor
public class WpProductDaoImpl implements WpProductDao {

    // оба коннекта у тебя уже сконфигурированы где-то как wpJdbcTemplate/wpNamedJdbc
    private final @Qualifier("wpNamedJdbc") NamedParameterJdbcTemplate wpNamedJdbc;

    @Override
    public List<CardTovExportService.ItemHash> collectSeenWindow(int limit, String cursorAfter) {
        int lim = Math.max(1, Math.min(limit, 1000));

        // ВАЖНО: если у тебя в WP нестандартный префикс (не wp_), поправь здесь имена таблиц!
        String sql = """
            SELECT sku.post_id            AS post_id,
                   sku.meta_value         AS sku,
                   COALESCE(h.meta_value, '') AS hash
            FROM wp_postmeta sku
            JOIN wp_posts p
              ON p.ID = sku.post_id
             AND p.post_type = 'product'
            LEFT JOIN wp_postmeta h
              ON h.post_id = sku.post_id
             AND h.meta_key = '_ms_hash'
            WHERE sku.meta_key = '_sku'
              AND (:afterSku IS NULL OR sku.meta_value > :afterSku)
            ORDER BY sku.meta_value ASC
            LIMIT :lim
        """;

        var params = new MapSqlParameterSource()
                .addValue("afterSku", (cursorAfter == null || cursorAfter.isBlank()) ? null : cursorAfter)
                .addValue("lim", lim);

        return wpNamedJdbc.query(sql, params, (rs, rowNum) -> mapRowToItemHash(rs));
    }

    private static ItemHash mapRowToItemHash(ResultSet rs) throws SQLException {
        String sku  = rs.getString("sku");
        String hash = rs.getString("hash");
        if (sku == null)  sku  = "";
        if (hash == null) hash = "";
        return new CardTovExportService.ItemHash(sku, hash);
    }
}