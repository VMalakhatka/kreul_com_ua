package org.example.proect.lavka.dao.wp;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.support.AbstractRetryingDao;
import org.example.proect.lavka.dto.SeenItem;
import org.example.proect.lavka.service.CardTovExportService;
import org.example.proect.lavka.service.CardTovExportService.ItemHash;
import org.example.proect.lavka.utils.RetryLabel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class WpProductDaoImpl extends AbstractRetryingDao implements WpProductDao{

    // оба коннекта у тебя уже сконфигурированы где-то как wpJdbcTemplate/wpNamedJdbc
    private final NamedParameterJdbcTemplate wpNamedJdbc;
    private final JdbcTemplate jdbc;

    private final String POSTS = "wp_posts";
    private final String PMETA = "wp_postmeta";

    public WpProductDaoImpl(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc
            , @Qualifier("wpNamedJdbc") NamedParameterJdbcTemplate wpNamedJdbc) {
        this.jdbc = jdbc;
        this.wpNamedJdbc = wpNamedJdbc;
    }


    @Override
    public List<String> listSkusBetween(String fromSku, String toSku, int limit, @Nullable String afterExclusive) {

        String afterCond = (afterExclusive != null && !afterExclusive.isBlank())
                ? "AND pm.meta_value > :afterSku"
                : "";

        // text block + placeholders
        String sql = """
            SELECT pm.meta_value AS sku
            FROM %s pm
            INNER JOIN %s p
                ON p.ID = pm.post_id
            WHERE pm.meta_key = '_sku'
              AND p.post_type = 'product'
              AND pm.meta_value >= :fromSku
              AND pm.meta_value <= :toSku
              %s
            ORDER BY pm.meta_value ASC
            LIMIT :lim
            """.formatted(PMETA, POSTS, afterCond);

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("fromSku", fromSku)
                .addValue("toSku", toSku)
                .addValue("lim", limit);

        if (!afterCond.isBlank()) {
            p.addValue("afterSku", afterExclusive.trim());
        }

        return withRetry("wp.listSkusBetween", () ->
                wpNamedJdbc.query(sql, p, (rs, i) -> rs.getString("sku"));
    }


    /** ID attachment по s3_key (wp_postmeta._wp_attached_file) или по guid (URL). */
    @Override
    public Long findAttachmentIdByS3KeyOrGuid(String s3Key, String guid) {
        // сначала по _wp_attached_file (надежнее)
        Long id = jdbc.query(
                "SELECT post_id FROM wp_postmeta WHERE meta_key='_wp_attached_file' AND meta_value=? LIMIT 1",
                ps -> ps.setString(1, s3Key),
                rs -> rs.next() ? rs.getLong(1) : null
        );
        if (id != null) return id;
        // fallback по guid
        return jdbc.query(
                "SELECT ID FROM wp_posts WHERE post_type='attachment' AND guid=? LIMIT 1",
                ps -> ps.setString(1, guid),
                rs -> rs.next() ? rs.getLong(1) : null
        );
    }

    /** Текущий featured attachment для товара. */
    @Override
    public Long findFeaturedId(long productId) {
        return jdbc.query(
                "SELECT meta_value FROM wp_postmeta WHERE post_id=? AND meta_key='_thumbnail_id' LIMIT 1",
                ps -> ps.setLong(1, productId),
                rs -> rs.next() ? rs.getLong(1) : null
        );
    }

    /** Массив ID в галерее (в порядке). */
    @Override
    public List<Long> findGalleryIds(long productId) {
        String csv = jdbc.query(
                "SELECT meta_value FROM wp_postmeta WHERE post_id=? AND meta_key='_product_image_gallery' LIMIT 1",
                ps -> ps.setLong(1, productId),
                rs -> rs.next() ? rs.getString(1) : null
        );
        if (csv == null || csv.isBlank()) return List.of();
        String[] parts = csv.split(",");
        List<Long> ids = new java.util.ArrayList<>(parts.length);
        for (String p : parts) try { ids.add(Long.parseLong(p.trim())); } catch (Exception ignore){}
        return ids;
    }

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