package org.example.proect.lavka.dao;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.mapper.CardTovExportRowMapper;
import org.example.proect.lavka.dto.CardTovExportDto;
import org.example.proect.lavka.utils.RetryLabel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

@RetryLabel("CardTovExportDaoImpl")
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
public class CardTovExportDaoImpl implements CardTovExportDao {

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
    public List<CardTovExportDto> findBetweenExcluding(String minSku, String maxSku,
                                                       Collection<String> exclude, int cap) {
        if (minSku == null || maxSku == null || minSku.equals(maxSku)) return List.of();
        int lim = Math.max(1, Math.min(cap, 2000)); // предохранитель
        // В T-SQL TOP нельзя параметризовать — подставляем безопасно отформатированное число.
        String sql = (exclude == null || exclude.isEmpty())
                ? """
                   SELECT TOP %d
                          sku, name,
                          NGROUP_TVR, NGROUP_TV2, NGROUP_TV3, NGROUP_TV4, NGROUP_TV5, NGROUP_TV6,
                          img, EDIN_IZMER, global_unique_id,
                          weight, [length], [width], [height],
                          status, VES_EDINIC, [DESCRIPTION], RAZM_IZMER, gr_descr
                   FROM dbo.card_tov_export
                   WHERE sku > :minSku AND sku < :maxSku
                   ORDER BY sku
                   """.formatted(lim)
                : """
                   SELECT TOP %d
                          sku, name,
                          NGROUP_TVR, NGROUP_TV2, NGROUP_TV3, NGROUP_TV4, NGROUP_TV5, NGROUP_TV6,
                          img, EDIN_IZMER, global_unique_id,
                          weight, [length], [width], [height],
                          status, VES_EDINIC, [DESCRIPTION], RAZM_IZMER, gr_descr
                   FROM dbo.card_tov_export
                   WHERE sku > :minSku AND sku < :maxSku
                     AND sku NOT IN (:exclude)
                   ORDER BY sku
                   """.formatted(lim);

        var params = new MapSqlParameterSource()
                .addValue("minSku", minSku)
                .addValue("maxSku", maxSku);

        if (exclude != null && !exclude.isEmpty()) {
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
}