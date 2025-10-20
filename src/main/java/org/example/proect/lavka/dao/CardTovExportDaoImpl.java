package org.example.proect.lavka.dao;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.mapper.CardTovExportRowMapper;
import org.example.proect.lavka.dto.CardTovExportDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class CardTovExportDaoImpl implements CardTovExportDao {

    private final @Qualifier("folioNamedJdbc") NamedParameterJdbcTemplate namedJdbc;

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
}