package org.example.proect.lavka.dao;


import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.CardTovExportDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Retryable(
        include = {
                DeadlockLoserDataAccessException.class,
                CannotAcquireLockException.class,
                QueryTimeoutException.class,
                TransientDataAccessResourceException.class
        },
        maxAttempts = 4,
        backoff = @Backoff(delay = 200, multiplier = 2.0, maxDelay = 5000, random = true)
)
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

        return namedJdbc.query(sql, params, (rs, i) -> new CardTovExportDto(
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("NGROUP_TVR"),
                rs.getString("NGROUP_TV2"),
                rs.getString("NGROUP_TV3"),
                rs.getString("NGROUP_TV4"),
                rs.getString("NGROUP_TV5"),
                rs.getString("NGROUP_TV6"),
                rs.getString("img"),
                rs.getString("EDIN_IZMER"),
                rs.getString("global_unique_id"),
                getD(rs, "weight"),
                getD(rs, "length"),
                getD(rs, "width"),
                getD(rs, "height"),
                getI(rs, "status"),
                getD(rs, "VES_EDINIC"),
                rs.getString("DESCRIPTION"),
                rs.getString("RAZM_IZMER"),
                rs.getString("gr_descr")   // <— НОВОЕ
        ));
    }

    private static Double getD(ResultSet rs, String col) throws SQLException {
        return (rs.getObject(col) == null) ? null : rs.getDouble(col);
    }
    private static Integer getI(ResultSet rs, String col) throws SQLException {
        return (rs.getObject(col) == null) ? null : rs.getInt(col);
    }
}