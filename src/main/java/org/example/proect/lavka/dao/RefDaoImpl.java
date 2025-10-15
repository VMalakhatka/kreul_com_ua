package org.example.proect.lavka.dao;

import org.example.proect.lavka.dto.ref.ContractDto;
import org.example.proect.lavka.dto.ref.OpTypeDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

import java.util.List;

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
public class RefDaoImpl implements RefDao {

    private final JdbcTemplate jdbc;

    public RefDaoImpl(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OpTypeDto> findOpTypes() {
        final String sql = """
            SELECT SIGNIFIC, PLANIR
            FROM dbo.VID_OPER
            WHERE SIGNIFIC IS NOT NULL AND LTRIM(RTRIM(SIGNIFIC)) <> ''
            ORDER BY SIGNIFIC
        """;

        return jdbc.query(sql, (rs, i) -> new OpTypeDto(
                rs.getString("SIGNIFIC"),
                rs.getObject("PLANIR") == null ? null : rs.getDouble("PLANIR")
        ));
    }

    @Override
    public List<ContractDto> findContracts() {
        final String sql = """
        SELECT SIGNIFIC, NAME_KONTR, ORGANIZ_KT
        FROM dbo._KONTRCT
        WHERE SIGNIFIC IS NOT NULL AND LTRIM(RTRIM(SIGNIFIC)) <> ''
        ORDER BY SIGNIFIC
    """;

        return jdbc.query(sql, (rs, i) -> new ContractDto(
                rs.getString("SIGNIFIC"),
                rs.getString("NAME_KONTR"),
                rs.getString("ORGANIZ_KT")
        ));
    }
}