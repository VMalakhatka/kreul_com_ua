package org.example.proect.lavka.dao.mapper;

import org.example.proect.lavka.dto.AssembleDtoOut;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
@Component
public class AssembleMapper implements RowMapper<AssembleDtoOut> {
    @Override
    public AssembleDtoOut mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AssembleDtoOut(
                rs.getString("ART").replaceAll("\\s+$", ""),
                rs.getString("ART_R").replaceAll("\\s+$", ""),
                rs.getDouble("KOL_R")
        );
    }
}
