package org.example.proect.lavka.dao.stock;

import org.example.proect.lavka.dto.stock.MsWarehouse;
import org.example.proect.lavka.utils.RetryLabel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

import java.util.List;

@RetryLabel("MsWarehouseDao")
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
public class MsWarehouseDao {

    private final JdbcTemplate jdbc;

    public MsWarehouseDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SQL_FIND_ALL_VISIBLE =
            "SELECT " +
                    "  CONVERT(VARCHAR(32), ID_SCLAD)        AS code, " +
                    "  LTRIM(RTRIM(NAME_SCLAD))              AS name, " +
                    "  CASE WHEN LTRIM(RTRIM(C_2)) = '' THEN NULL " +
                    "       ELSE LTRIM(RTRIM(C_2)) END       AS descr " +
                    "FROM dbo.SCLAD_R " +
                    "WHERE LTRIM(RTRIM(C_1)) = '1' " +
                    "ORDER BY ID_SCLAD";

    public List<MsWarehouse> findAllVisible() {
        return jdbc.query(SQL_FIND_ALL_VISIBLE, (rs, i) -> new MsWarehouse(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("descr")
        ));
    }
}