package org.example.proect.lavka.dao.mapper;

import org.example.proect.lavka.entity.SclArtc;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public class SclArtcMapper implements RowMapper<SclArtc> {
    @Override
    public SclArtc mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SclArtc(
                rs.getString("COD_ARTIC").replaceAll("\\s+$", ""),
                rs.getString("NAME_ARTIC"),
                rs.getDouble("CENA_VALT"),
                rs.getString("COD_VALT"),
                rs.getDouble("KON_KOLCH"),
                rs.getDouble("REZ_KOLCH"),
                rs.getString("EDIN_IZMER"),
                rs.getDouble("EDN_V_UPAK"),
                rs.getString("DOP2_ARTIC"),
                rs.getString("DOP3_ARTIC"),
                rs.getDouble("MIN_TVRZAP"),
                rs.getDouble("MAX_TVRZAP"),
                rs.getInt("ID_SCLAD"),
                rs.getDouble("BALL1"),
                rs.getDouble("BALL2"),
                rs.getDouble("BALL4"),
                rs.getDouble("BALL5"),
                rs.getString("TIP_TOVR")
                );
    }
}
