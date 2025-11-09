package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.utils.RetryLabel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RetryLabel("S3MediaIndexDao")
@Repository
public class S3MediaIndexDao {

    private final JdbcTemplate jdbc;

    public S3MediaIndexDao(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ======= существующая часть =======

    public record Row(
            String filenameLower,
            String fullKey,
            long sizeBytes,
            @Nullable Instant lastModified,
            @Nullable String etag
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
                if (r.lastModified() == null) ps.setTimestamp(4, null);
                else ps.setTimestamp(4, Timestamp.from(r.lastModified()));
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

    // ======= новое: связи картинка ↔ SKU/товар =======

    /** Возвращает id наиболее "свежей" записи картинки по имени файла. */
    public @Nullable Long resolveImageIdByFilename(String filenameLower) {
        return jdbc.query("""
        SELECT id
        FROM s3_media_index
        WHERE filename_lower = ?
        ORDER BY (last_modified IS NULL) ASC, last_modified DESC, size_bytes DESC
        LIMIT 1
        """,
                ps -> ps.setString(1, filenameLower.toLowerCase()),
                rs -> rs.next() ? rs.getLong(1) : null
        );
    }

    /** Привязать картинку (по имени файла) к списку SKU: позиции идут подряд, начиная со startPos (или 0). */
    public int[] upsertLinksByFileNameAndSkus(String filenameLower, List<String> skus, @Nullable Integer startPos) {
        Long imageId = resolveImageIdByFilename(filenameLower);
        if (imageId == null) {
            throw new IllegalStateException("Image not found by filename: " + filenameLower);
        }
        int base = startPos == null ? 0 : Math.max(0, startPos);
        return upsertLinksBatchByImageId(
                imageId,
                skus,
                null,          // productId
                base
        );
    }

    /** Привязать картинку (по имени файла) к product_id. */
    public int upsertLinkByFileNameAndProductId(String filenameLower, Long productId, @Nullable Integer position) {
        Objects.requireNonNull(productId, "productId is required");
        Long imageId = resolveImageIdByFilename(filenameLower);
        if (imageId == null) {
            throw new IllegalStateException("Image not found by filename: " + filenameLower);
        }
        int pos = position == null ? 0 : Math.max(0, position);
        // один элемент
        return upsertOneLinkByImageId(imageId, null, productId, pos);
    }

    /** Батч: image_id + список SKU, позиции: base, base+1, ... */
    public int[] upsertLinksBatchByImageId(long imageId, List<String> skus, @Nullable Long productId, int basePosition) {
        if (skus == null || skus.isEmpty()) return new int[0];

        final String sqlSku = """
            INSERT INTO s3_media_links (image_id, sku, position, pending_meta, pending_link)
            VALUES (?, ?, ?, 1, 1)
            ON DUPLICATE KEY UPDATE
              pending_meta = 1,
              pending_link = 1,
              updated_at = CURRENT_TIMESTAMP
            """;

        return jdbc.batchUpdate(sqlSku, new BatchPreparedStatementSetter() {
            @Override public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                String sku = skus.get(i);
                ps.setLong(1, imageId);
                ps.setString(2, sku);
                ps.setInt(3, basePosition + i);
            }
            @Override public int getBatchSize() { return skus.size(); }
        });
    }

    /** Одна запись: либо по SKU, либо по productId (одно из них обязательно). */
    public int upsertOneLinkByImageId(long imageId, @Nullable String sku, @Nullable Long productId, int position) {
        if (sku == null && productId == null) {
            throw new IllegalArgumentException("Either sku or productId must be provided");
        }
        if (sku != null) {
            return jdbc.update("""
                INSERT INTO s3_media_links (image_id, sku, position, pending_meta, pending_link)
                VALUES (?, ?, ?, 1, 1)
                ON DUPLICATE KEY UPDATE
                  pending_meta = 1,
                  pending_link = 1,
                  updated_at = CURRENT_TIMESTAMP
                """,
                    ps -> {
                        ps.setLong(1, imageId);
                        ps.setString(2, sku);
                        ps.setInt(3, position);
                    }
            );
        } else {
            return jdbc.update("""
                INSERT INTO s3_media_links (image_id, product_id, position, pending_meta, pending_link)
                VALUES (?, ?, ?, 1, 1)
                ON DUPLICATE KEY UPDATE
                  pending_meta = 1,
                  pending_link = 1,
                  updated_at = CURRENT_TIMESTAMP
                """,
                    ps -> {
                        ps.setLong(1, imageId);
                        ps.setLong(2, productId);
                        ps.setInt(3, position);
                    }
            );
        }
    }

    /** Пометить alt/title для последующей синхронизации в WP (pending_meta=1). */
    public int upsertAltTitle(long imageId, @Nullable String sku, @Nullable Long productId, int position,
                              @Nullable String altText, @Nullable String titleText) {
        if (sku == null && productId == null) {
            throw new IllegalArgumentException("Either sku or productId must be provided");
        }
        if (sku != null) {
            return jdbc.update("""
                INSERT INTO s3_media_links (image_id, sku, position, alt_text, title_text, pending_meta, pending_link)
                VALUES (?, ?, ?, ?, ?, 1, 1)
                ON DUPLICATE KEY UPDATE
                  alt_text     = VALUES(alt_text),
                  title_text   = VALUES(title_text),
                  pending_meta = 1,
                  updated_at   = CURRENT_TIMESTAMP
                """,
                    ps -> {
                        ps.setLong(1, imageId);
                        ps.setString(2, sku);
                        ps.setInt(3, position);
                        ps.setString(4, altText);
                        ps.setString(5, titleText);
                    }
            );
        } else {
            return jdbc.update("""
                INSERT INTO s3_media_links (image_id, product_id, position, alt_text, title_text, pending_meta, pending_link)
                VALUES (?, ?, ?, ?, ?, 1, 1)
                ON DUPLICATE KEY UPDATE
                  alt_text     = VALUES(alt_text),
                  title_text   = VALUES(title_text),
                  pending_meta = 1,
                  updated_at   = CURRENT_TIMESTAMP
                """,
                    ps -> {
                        ps.setLong(1, imageId);
                        ps.setLong(2, productId);
                        ps.setInt(3, position);
                        ps.setString(4, altText);
                        ps.setString(5, titleText);
                    }
            );
        }
    }

    public List<String> findFullKeysBySku(String sku) {
        return jdbc.query("""
        SELECT i.full_key
        FROM s3_media_links l
        JOIN s3_media_index i ON i.id = l.image_id
        WHERE l.sku = ?
        ORDER BY l.position ASC, i.last_modified DESC, i.size_bytes DESC
        """,
                (rs, n) -> rs.getString(1),
                sku
        );
    }


    /** Удобный помощник: найти image_id по full_key (как хранится в s3_media_index.full_key). */
    public @Nullable Long resolveImageIdByFullKey(String fullKey) {
        return jdbc.query(
                "SELECT id FROM s3_media_index WHERE full_key = ? LIMIT 1",
                ps -> ps.setString(1, fullKey),
                rs -> rs.next() ? rs.getLong(1) : null
        );
    }

    /** Вариант: найти image_id по attachedFile (т.е. по уже урезанному пути YYYY/MM/file). */
    public @Nullable Long resolveImageIdByAttachedFile(String attachedFile) {
        // так как в индексе хранится full_key, добавим префикс "wp-content/uploads/" для поиска
        String fullKey = "wp-content/uploads/" + attachedFile.replace('\\','/').replaceAll("^/+", "");
        return resolveImageIdByFullKey(fullKey);
    }

    /** Есть ли уже линк по sku+imageId+position? */
    public boolean linkExists(String sku, long imageId, int position) {
        Integer cnt = jdbc.query("""
        SELECT 1 FROM s3_media_links
        WHERE sku=? AND image_id=? AND position=?
        LIMIT 1
    """, ps -> { ps.setString(1, sku); ps.setLong(2, imageId); ps.setInt(3, position); },
                rs -> rs.next() ? 1 : 0
        );
        return cnt != null && cnt == 1;
    }

    /** Список позиций (position -> image_id) уже связанных для SKU. */
    public Map<Integer, Long> positionsForSku(String sku) {
        return jdbc.query("""
        SELECT position, image_id
        FROM s3_media_links
        WHERE sku=?
    """, ps -> ps.setString(1, sku),
                rs -> {
                    Map<Integer,Long> m = new java.util.HashMap<>();
                    while (rs.next()) m.put(rs.getInt(1), rs.getLong(2));
                    return m;
                }
        );
    }

    // S3MediaIndexDao

    /** Лучший full_key по имени файла (как в индексе: last_modified DESC, size DESC). */
    public @Nullable String resolveBestFullKeyByFilename(String filenameLower) {
        return jdbc.query("""
        SELECT full_key
        FROM s3_media_index
        WHERE filename_lower = ?
        ORDER BY last_modified DESC, size_bytes DESC
        LIMIT 1
    """,
                ps -> ps.setString(1, filenameLower.toLowerCase()),
                rs -> rs.next() ? rs.getString(1) : null
        );
    }

    /** Быстрый «есть ли линк» по SKU+attachedFile (YYYY/MM/file). */
    public boolean linkExistsByAttachedFile(String sku, String attachedFile, int position) {
        String fullKey = "wp-content/uploads/" + attachedFile.replace('\\','/').replaceAll("^/+", "");
        Long imageId = resolveImageIdByFullKey(fullKey);
        if (imageId == null) return false;
        return linkExists(sku, imageId, position);
    }
}