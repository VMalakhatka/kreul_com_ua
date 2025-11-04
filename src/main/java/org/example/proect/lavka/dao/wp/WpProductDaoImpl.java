package org.example.proect.lavka.dao.wp;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.SeenItem;
import org.example.proect.lavka.service.CardTovExportService;
import org.example.proect.lavka.service.CardTovExportService.ItemHash;
import org.example.proect.lavka.utils.RetryLabel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

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
    public List<SeenItem> collectSeenWindow(int limit, @Nullable String afterSku) {
        // build SQL with optional afterSku
        String baseSql = """
        SELECT sku.post_id           AS post_id,
               sku.meta_value        AS sku,
               COALESCE(h.meta_value, '') AS hash
        FROM wp_postmeta sku
        JOIN wp_posts p
          ON p.ID = sku.post_id
         AND p.post_type = 'product'
        LEFT JOIN wp_postmeta h
          ON h.post_id = sku.post_id
         AND h.meta_key = '_ms_hash'
        WHERE sku.meta_key = '_sku'
        /**AFTER_COND**/
        ORDER BY sku.meta_value ASC
        LIMIT :lim
    """;

        Map<String,Object> params = new HashMap<>();
        params.put("lim", Math.max(1, Math.min(limit, 1000)));

        String sql;
        if (afterSku != null && !afterSku.isBlank()) {
            sql = baseSql.replace("/**AFTER_COND**/", "AND sku.meta_value > :afterSku");
            params.put("afterSku", afterSku.trim());
        } else {
            sql = baseSql.replace("/**AFTER_COND**/", "");
        }

        return wpNamedJdbc.query(sql, params, (rs, rowNum) -> new SeenItem(
                rs.getString("sku"),
                rs.getString("hash"),
                rs.getLong("post_id")
        ));
    }

    @Override
    public Map<String, Long> findIdsBySkus(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return Map.of();
        }

        String sql = """
            SELECT meta_value AS sku, post_id
            FROM wp_postmeta
            WHERE meta_key = '_sku'
              AND meta_value IN (:skus)
        """;

        Map<String, Object> params = Map.of("skus", skus);

        return wpNamedJdbc.query(sql, params, rs -> {
            Map<String, Long> map = new LinkedHashMap<>();
            while (rs.next()) {
                String sku = rs.getString("sku");
                Long id = rs.getLong("post_id");
                if (sku != null && id != null) {
                    map.put(sku, id);
                }
            }
            return map;
        });
    }


    private static ItemHash mapRowToItemHash(ResultSet rs) throws SQLException {
        String sku  = rs.getString("sku");
        String hash = rs.getString("hash");
        if (sku == null)  sku  = "";
        if (hash == null) hash = "";
        return new CardTovExportService.ItemHash(sku, hash);
    }
}