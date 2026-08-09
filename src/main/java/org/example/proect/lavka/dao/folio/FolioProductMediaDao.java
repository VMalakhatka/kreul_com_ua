package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class FolioProductMediaDao {

    private final JdbcTemplate jdbc;

    public FolioProductMediaDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ProductRow(
            String sku,
            @Nullable String productName,
            @Nullable String mainFilename,
            long plusArtic
    ) {
    }

    public record MediaRow(
            String role,
            String sku,
            @Nullable String productName,
            @Nullable String filename,
            long plusArtic,
            @Nullable Integer sortOrder,
            String recordKey
    ) {
    }

    public record GalleryRow(
            int id,
            long plusArtic,
            @Nullable String filename,
            @Nullable Integer sortOrder
    ) {
    }

    public @Nullable ProductRow findProduct(String sku, boolean forUpdate) {
        String lock = forUpdate ? " WITH (UPDLOCK, HOLDLOCK)" : "";
        String sql = """
                SELECT a.COD_ARTIC AS sku,
                       (SELECT MIN(s.NAME_ARTIC)
                          FROM dbo.SCL_ARTC s
                         WHERE s.COD_ARTIC = a.COD_ARTIC) AS productName,
                       a.S50 AS mainFilename,
                       a.PLUS_ARTIC AS plusArtic
                  FROM dbo.ALL_ARTC a%s
                 WHERE a.COD_ARTIC = ?
                """.formatted(lock);
        List<ProductRow> rows = jdbc.query(sql, (rs, rowNum) -> new ProductRow(
                rs.getString("sku"),
                rs.getString("productName"),
                rs.getString("mainFilename"),
                rs.getLong("plusArtic")
        ), sku);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    public int countProductsByPlusArtic(long plusArtic) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dbo.ALL_ARTC WHERE PLUS_ARTIC = ?",
                Integer.class,
                plusArtic
        );
        return count == null ? 0 : count;
    }

    public List<MediaRow> searchMain(@Nullable String sku, @Nullable String exactFilename) {
        StringBuilder sql = new StringBuilder("""
                SELECT 'main' AS role,
                       a.COD_ARTIC AS sku,
                       (SELECT MIN(s.NAME_ARTIC)
                          FROM dbo.SCL_ARTC s
                         WHERE s.COD_ARTIC = a.COD_ARTIC) AS productName,
                       a.S50 AS filename,
                       a.PLUS_ARTIC AS plusArtic,
                       CAST(NULL AS int) AS sortOrder,
                       a.COD_ARTIC AS recordKey
                  FROM dbo.ALL_ARTC a
                 WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (sku != null) {
            sql.append(" AND a.COD_ARTIC = ?");
            args.add(sku);
        }
        if (exactFilename != null) {
            sql.append(" AND a.S50 = ?");
            args.add(exactFilename);
        }
        return jdbc.query(sql.toString(), (rs, rowNum) -> new MediaRow(
                rs.getString("role"),
                rs.getString("sku"),
                rs.getString("productName"),
                rs.getString("filename"),
                rs.getLong("plusArtic"),
                (Integer) rs.getObject("sortOrder"),
                rs.getString("recordKey")
        ), args.toArray());
    }

    public List<MediaRow> searchGallery(@Nullable String sku, @Nullable String exactFilename) {
        StringBuilder sql = new StringBuilder("""
                SELECT 'gallery' AS role,
                       a.COD_ARTIC AS sku,
                       (SELECT MIN(s.NAME_ARTIC)
                          FROM dbo.SCL_ARTC s
                         WHERE s.COD_ARTIC = a.COD_ARTIC) AS productName,
                       g.image AS filename,
                       g.PLUS_ARTIC AS plusArtic,
                       g.sort_order AS sortOrder,
                       CONVERT(varchar(20), g.id) AS recordKey
                  FROM dbo.img_prod g
                  JOIN dbo.ALL_ARTC a ON a.PLUS_ARTIC = g.PLUS_ARTIC
                 WHERE g.image IS NOT NULL
                """);
        List<Object> args = new ArrayList<>();
        if (sku != null) {
            sql.append(" AND a.COD_ARTIC = ?");
            args.add(sku);
        }
        if (exactFilename != null) {
            sql.append(" AND g.image = ?");
            args.add(exactFilename);
        }
        return jdbc.query(sql.toString(), (rs, rowNum) -> new MediaRow(
                rs.getString("role"),
                rs.getString("sku"),
                rs.getString("productName"),
                rs.getString("filename"),
                rs.getLong("plusArtic"),
                (Integer) rs.getObject("sortOrder"),
                rs.getString("recordKey")
        ), args.toArray());
    }

    public @Nullable GalleryRow findGalleryById(int id, boolean forUpdate) {
        String lock = forUpdate ? " WITH (UPDLOCK, HOLDLOCK)" : "";
        String sql = "SELECT id, PLUS_ARTIC, image, sort_order FROM dbo.img_prod" + lock + " WHERE id = ?";
        List<GalleryRow> rows = jdbc.query(sql, (rs, rowNum) -> new GalleryRow(
                rs.getInt("id"),
                rs.getLong("PLUS_ARTIC"),
                rs.getString("image"),
                (Integer) rs.getObject("sort_order")
        ), id);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    public List<GalleryRow> findGalleryByPlusArtic(long plusArtic, boolean forUpdate) {
        String lock = forUpdate ? " WITH (UPDLOCK, HOLDLOCK)" : "";
        String sql = "SELECT id, PLUS_ARTIC, image, sort_order FROM dbo.img_prod" + lock
                + " WHERE PLUS_ARTIC = ? ORDER BY sort_order, id";
        return jdbc.query(sql, (rs, rowNum) -> new GalleryRow(
                rs.getInt("id"),
                rs.getLong("PLUS_ARTIC"),
                rs.getString("image"),
                (Integer) rs.getObject("sort_order")
        ), plusArtic);
    }

    public int updateMain(String sku, @Nullable String expectedOldFilename, String filename) {
        return jdbc.update("""
                UPDATE dbo.ALL_ARTC
                   SET S50 = ?
                 WHERE COD_ARTIC = ?
                   AND (S50 = ? OR (S50 IS NULL AND ? IS NULL))
                """, filename, sku, expectedOldFilename, expectedOldFilename);
    }

    public int updateGallery(int id,
                             long plusArtic,
                             @Nullable String expectedOldFilename,
                             @Nullable Integer expectedOldSortOrder,
                             String filename,
                             int sortOrder) {
        return jdbc.update("""
                UPDATE dbo.img_prod
                   SET image = ?, sort_order = ?
                 WHERE id = ?
                   AND PLUS_ARTIC = ?
                   AND (image = ? OR (image IS NULL AND ? IS NULL))
                   AND (sort_order = ? OR (sort_order IS NULL AND ? IS NULL))
                """, filename, sortOrder, id, plusArtic,
                expectedOldFilename, expectedOldFilename,
                expectedOldSortOrder, expectedOldSortOrder);
    }

    public int insertGallery(long plusArtic, String filename, int sortOrder) {
        jdbc.update("INSERT INTO dbo.img_prod (PLUS_ARTIC, image, sort_order) VALUES (?, ?, ?)",
                plusArtic, filename, sortOrder);
        Integer id = jdbc.queryForObject("SELECT CAST(@@IDENTITY AS int)", Integer.class);
        if (id == null) {
            throw new IllegalStateException("Cannot read generated dbo.img_prod.id");
        }
        return id;
    }
}
