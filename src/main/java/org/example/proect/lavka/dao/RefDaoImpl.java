package org.example.proect.lavka.dao;

import org.example.proect.lavka.dto.ref.OpTypeDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
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
}