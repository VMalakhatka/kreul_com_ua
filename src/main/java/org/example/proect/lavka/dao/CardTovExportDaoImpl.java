package org.example.proect.lavka.dao;

import jakarta.annotation.Nullable;
import org.example.proect.lavka.dao.support.AbstractRetryingDao;
import org.example.proect.lavka.dao.mapper.CardTovExportRowMapper;
import org.example.proect.lavka.dto.CardTovExportDto;
import org.example.proect.lavka.utils.RetryLabel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import org.springframework.stereotype.Repository;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

@Slf4j
@RetryLabel("CardTovExportDaoImpl")
@Repository
public class CardTovExportDaoImpl extends AbstractRetryingDao implements CardTovExportDao {

    private static final String BASE_SELECT = """
        SELECT
               sku,
               name,
               NGROUP_TVR,
               NGROUP_TV2,
               NGROUP_TV3,
               NGROUP_TV4,
               NGROUP_TV5,
               NGROUP_TV6,
               img,
               EDIN_IZMER,
               global_unique_id,
               weight,
               [length],
               [width],
               [height],
               status,
               VES_EDINIC,
               [DESCRIPTION],
               RAZM_IZMER,
               gr_descr
          FROM dbo.card_tov_export
        """;


    private final NamedParameterJdbcTemplate namedJdbc;

    public CardTovExportDaoImpl(
            @Qualifier("folioNamedJdbc") NamedParameterJdbcTemplate namedJdbc
    ) {
        this.namedJdbc = namedJdbc;
    }

    @Override
    public List<CardTovExportDto> findPage(String after, int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));

        String sql = """
            SELECT TOP %d
                   sku,
                   name,
                   NGROUP_TVR,
                   NGROUP_TV2,
                   NGROUP_TV3,
                   NGROUP_TV4,
                   NGROUP_TV5,
                   NGROUP_TV6,
                   img,
                   EDIN_IZMER,
                   global_unique_id,
                   weight,
                   [length],
                   [width],
                   [height],
                   status,
                   VES_EDINIC,
                   [DESCRIPTION],
                   RAZM_IZMER,
                   gr_descr
            FROM dbo.card_tov_export
            WHERE (:after IS NULL OR sku > :after)
            ORDER BY sku
            """.formatted(lim);

        var params = new MapSqlParameterSource()
                .addValue("after", (after == null || after.isBlank()) ? null : after);

        return namedJdbc.query(sql, params, CardTovExportRowMapper.M);
    }

    @Override
    public List<CardTovExportDto> findBySkus(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) return List.of();
        String sql = """
            SELECT
                   sku,
                   name,
                   NGROUP_TVR,
                   NGROUP_TV2,
                   NGROUP_TV3,
                   NGROUP_TV4,
                   NGROUP_TV5,
                   NGROUP_TV6,
                   img,
                   EDIN_IZMER,
                   global_unique_id,
                   weight,
                   [length],
                   [width],
                   [height],
                   status,
                   VES_EDINIC,
                   [DESCRIPTION],
                   RAZM_IZMER,
                   gr_descr
            FROM dbo.card_tov_export
            WHERE sku IN (:skus)
            """;
        var params = new MapSqlParameterSource().addValue("skus", skus);
        return namedJdbc.query(sql, params, CardTovExportRowMapper.M);
    }

@Override
public List<CardTovExportDto> findBetweenExcluding(
        String minSku, String maxSku,
        Collection<String> exclude, int cap
) {
    if (minSku == null || maxSku == null || minSku.equals(maxSku)) return List.of();
    int lim = Math.max(1, Math.min(cap, 2000));
    boolean hasExclude = exclude != null && !exclude.isEmpty();

    String sql = (hasExclude)
            ? """
           SELECT TOP %d
                  a.COD_ARTIC AS sku, a.NAME_ARTIC AS name,
                  a.NGROUP_TVR, a.NGROUP_TV2, a.NGROUP_TV3, a.NGROUP_TV4, a.NGROUP_TV5, a.NGROUP_TV6,
                  b.S50 AS img, a.EDIN_IZMER, a.DOP3_ARTIC AS global_unique_id,
                  a.BALL3 AS weight, a.DLINA_ART AS [length], a.SHIRIN_ART AS [width], a.VYSOTA_ART AS [height],
                  a.DEPARTAM AS [status], a.VES_EDINIC, b.[DESCRIPTION], a.RAZM_IZMER, b.S255 AS gr_descr
           FROM dbo.SCL_ARTC a
           LEFT JOIN dbo.ALL_ARTC b ON b.COD_ARTIC = a.COD_ARTIC
           WHERE a.ID_SCLAD = 7
             AND CONVERT(VARBINARY(900), a.COD_ARTIC) > CONVERT(VARBINARY(900), CAST(:minSku AS VARCHAR(100)))
             AND CONVERT(VARBINARY(900), a.COD_ARTIC) <= CONVERT(VARBINARY(900), CAST(:maxSku AS VARCHAR(100)))
             AND a.COD_ARTIC NOT IN (:exclude)
           ORDER BY CONVERT(VARBINARY(900), a.COD_ARTIC)
           """.formatted(lim)
            : """
           SELECT TOP %d
                  a.COD_ARTIC AS sku, a.NAME_ARTIC AS name,
                  a.NGROUP_TVR, a.NGROUP_TV2, a.NGROUP_TV3, a.NGROUP_TV4, a.NGROUP_TV5, a.NGROUP_TV6,
                  b.S50 AS img, a.EDIN_IZMER, a.DOP3_ARTIC AS global_unique_id,
                  a.BALL3 AS weight, a.DLINA_ART AS [length], a.SHIRIN_ART AS [width], a.VYSOTA_ART AS [height],
                  a.DEPARTAM AS [status], a.VES_EDINIC, b.[DESCRIPTION], a.RAZM_IZMER, b.S255 AS gr_descr
           FROM dbo.SCL_ARTC a
           LEFT JOIN dbo.ALL_ARTC b ON b.COD_ARTIC = a.COD_ARTIC
           WHERE a.ID_SCLAD = 7
             AND CONVERT(VARBINARY(900), a.COD_ARTIC) > CONVERT(VARBINARY(900), CAST(:minSku AS VARCHAR(100)))
             AND CONVERT(VARBINARY(900), a.COD_ARTIC) <= CONVERT(VARBINARY(900), CAST(:maxSku AS VARCHAR(100)))
           ORDER BY CONVERT(VARBINARY(900), a.COD_ARTIC)
           """.formatted(lim);

    var params = new MapSqlParameterSource()
            .addValue("minSku", minSku)
            .addValue("maxSku", maxSku);

    if (hasExclude) {
        params.addValue("exclude", exclude);
    }

    return namedJdbc.query(sql, params, CardTovExportRowMapper.M);
}


    @Override
    public List<CardTovExportDto> findLessThanExcluding(String maxExclusive,
                                                        Collection<String> exclude,
                                                        int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        boolean hasExclude = (exclude != null && !exclude.isEmpty());

        String excludeClause = hasExclude ? " AND sku NOT IN (:exclude)\n" : "";

        String sql = ("""
        SELECT TOP %d
               sku, name,
               NGROUP_TVR, NGROUP_TV2, NGROUP_TV3, NGROUP_TV4, NGROUP_TV5, NGROUP_TV6,
               img, EDIN_IZMER, global_unique_id,
               weight, [length], [width], [height],
               status, VES_EDINIC, [DESCRIPTION], RAZM_IZMER, gr_descr
        FROM dbo.card_tov_export
        WHERE sku < :max
        %s
        ORDER BY sku ASC
    """).formatted(lim, excludeClause);

        var params = new MapSqlParameterSource().addValue("max", maxExclusive);
        if (hasExclude) params.addValue("exclude", exclude);

        return namedJdbc.query(sql, params, CardTovExportRowMapper.M);
    }

    @Override
    public List<CardTovExportDto> findGreaterThan(String minExclusive, int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));

        String sql = ("""
        SELECT TOP %d
               sku, name,
               NGROUP_TVR, NGROUP_TV2, NGROUP_TV3, NGROUP_TV4, NGROUP_TV5, NGROUP_TV6,
               img, EDIN_IZMER, global_unique_id,
               weight, [length], [width], [height],
               status, VES_EDINIC, [DESCRIPTION], RAZM_IZMER, gr_descr
        FROM dbo.card_tov_export
        WHERE sku > :min
        ORDER BY sku ASC
    """).formatted(lim);

        var params = new MapSqlParameterSource().addValue("min", minExclusive);
        return namedJdbc.query(sql, params, CardTovExportRowMapper.M);
    }

    private static CardTovExportDto mapRow(ResultSet rs, int i) throws SQLException {
        CardTovExportDto d = new CardTovExportDto();
        d.setSku(rs.getString("sku"));
        d.setName(rs.getString("name"));
        d.setNGROUP_TVR(rs.getString("NGROUP_TVR"));
        d.setNGROUP_TV2(rs.getString("NGROUP_TV2"));
        d.setNGROUP_TV3(rs.getString("NGROUP_TV3"));
        d.setNGROUP_TV4(rs.getString("NGROUP_TV4"));
        d.setNGROUP_TV5(rs.getString("NGROUP_TV5"));
        d.setNGROUP_TV6(rs.getString("NGROUP_TV6"));
        d.setImg(rs.getString("img"));
        d.setEDIN_IZMER(rs.getString("EDIN_IZMER"));
        d.setGlobal_unique_id(rs.getString("global_unique_id"));
        d.setWeight((Double) rs.getObject("weight"));  // getObject → Double (может быть NULL)
        d.setLength((Double) rs.getObject("length"));
        d.setWidth ((Double) rs.getObject("width"));
        d.setHeight((Double) rs.getObject("height"));
        d.setStatus((Integer) rs.getObject("status"));
        d.setVES_EDINIC((Double) rs.getObject("VES_EDINIC"));
        d.setDESCRIPTION(rs.getString("DESCRIPTION"));
        d.setRAZM_IZMER(rs.getString("RAZM_IZMER"));
        d.setGr_descr(rs.getString("gr_descr"));
        return d;
    }

    @Override
    public List<CardTovExportDto> findGreaterThanExcluding(String lowerExclusive,
                                                           Collection<String> excludeSkus,
                                                           int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        boolean hasExclude = (excludeSkus != null && !excludeSkus.isEmpty());

        String excludeClause = hasExclude ? " AND sku NOT IN (:exclude)\n" : "";

        String sql = ("""
        SELECT TOP %d
               sku, name,
               NGROUP_TVR, NGROUP_TV2, NGROUP_TV3, NGROUP_TV4, NGROUP_TV5, NGROUP_TV6,
               img, EDIN_IZMER, global_unique_id,
               weight, [length], [width], [height],
               status, VES_EDINIC, [DESCRIPTION], RAZM_IZMER, gr_descr
        FROM dbo.card_tov_export
        WHERE 1=1
          AND (:lower IS NULL OR sku > :lower)
        %s
        ORDER BY sku ASC
    """).formatted(lim, excludeClause);

        var params = new MapSqlParameterSource()
                .addValue("lower", (lowerExclusive == null || lowerExclusive.isBlank()) ? null : lowerExclusive.trim());
        if (hasExclude) params.addValue("exclude", excludeSkus);

        return namedJdbc.query(sql, params, CardTovExportRowMapper.M);
    }

    // внизу класса CardTovExportDaoImpl
    private static Double getD(ResultSet rs, String col) throws SQLException {
        return (rs.getObject(col) == null) ? null : rs.getDouble(col);
    }

    private static Integer getI(ResultSet rs, String col) throws SQLException {
        return (rs.getObject(col) == null) ? null : rs.getInt(col);
    }

    public record MsCardImages(
            String sku,
            String mainFileName,   // из ALL_ARTC.S50
            String plusArtic       // связь на галерею
    ) {}

    public record MsGalleryImage(
            String fileName,       // dbo.img_prod.image
            Integer sortOrder      // dbo.img_prod.sort_order
    ) {}

    /** Основное изображение и ключ связи (PLUS_ARTIC) по SKU. */
    @Override
    public @Nullable MsCardImages findCardImagesBySku(String sku) {
        String sql = """
        SELECT a.COD_ARTIC AS sku,
               b.S50       AS mainFileName,
               b.PLUS_ARTIC AS plusArtic
        FROM dbo.SCL_ARTC a
        LEFT JOIN dbo.ALL_ARTC b ON b.COD_ARTIC = a.COD_ARTIC
        WHERE a.COD_ARTIC = :sku
        """;
        var params = new MapSqlParameterSource().addValue("sku", sku);
        return namedJdbc.query(sql, params, rs -> {
            if (!rs.next()) return null;
            return new MsCardImages(
                    rs.getString("sku"),
                    rs.getString("mainFileName"),
                    rs.getString("plusArtic")
            );
        });
    }

    /** Галерея по PLUS_ARTIC (если null — вернёт пусто). */
    @Override
    public List<MsGalleryImage> findGalleryByPlusArtic(String plusArtic) {
        if (plusArtic == null || plusArtic.isBlank()) return List.of();
        String sql = """
        SELECT image AS fileName, sort_order
        FROM dbo.img_prod
        WHERE PLUS_ARTIC = :pa
        ORDER BY sort_order ASC, image ASC
        """;
        var params = new MapSqlParameterSource().addValue("pa", plusArtic);
        return namedJdbc.query(sql, params, (rs,i) ->
                new MsGalleryImage(rs.getString("fileName"), (Integer) rs.getObject("sort_order"))
        );
    }

    /** Удобный обёртка-метод: сразу вернуть и main, и gallery для SKU. */
    public record MsImagesBundle(
            String sku,
            @Nullable String mainFileName,
            List<MsGalleryImage> gallery
    ) {}

    @Override
    public MsImagesBundle findImagesBundleBySku(String sku) {
        MsCardImages base = findCardImagesBySku(sku);
        if (base == null) return new MsImagesBundle(sku, null, List.of());
        List<MsGalleryImage> gal = findGalleryByPlusArtic(base.plusArtic());
        return new MsImagesBundle(sku, base.mainFileName(), gal);
    }
}