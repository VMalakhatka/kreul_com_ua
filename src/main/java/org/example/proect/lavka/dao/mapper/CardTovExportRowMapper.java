package org.example.proect.lavka.dao.mapper;


import org.example.proect.lavka.dto.CardTovExportDto;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Универсальный RowMapper для чтения товаров (card_tov_export) из MSSQL.
 */
public class CardTovExportRowMapper implements RowMapper<CardTovExportDto> {

    public static final CardTovExportRowMapper M = new CardTovExportRowMapper();

    @Override
    public CardTovExportDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        CardTovExportDto x = new CardTovExportDto();
        x.setSku(rs.getString("sku"));
        x.setName(rs.getString("name"));
        x.setNGROUP_TVR(rs.getString("NGROUP_TVR"));
        x.setNGROUP_TV2(rs.getString("NGROUP_TV2"));
        x.setNGROUP_TV3(rs.getString("NGROUP_TV3"));
        x.setNGROUP_TV4(rs.getString("NGROUP_TV4"));
        x.setNGROUP_TV5(rs.getString("NGROUP_TV5"));
        x.setNGROUP_TV6(rs.getString("NGROUP_TV6"));
        x.setImg(rs.getString("img"));
        x.setEDIN_IZMER(rs.getString("EDIN_IZMER"));
        x.setGlobal_unique_id(rs.getString("global_unique_id"));
        x.setWeight(rs.getObject("weight") != null ? rs.getDouble("weight") : null);
        x.setLength(rs.getObject("length") != null ? rs.getDouble("length") : null);
        x.setWidth(rs.getObject("width") != null ? rs.getDouble("width") : null);
        x.setHeight(rs.getObject("height") != null ? rs.getDouble("height") : null);
        x.setStatus(rs.getObject("status") != null ? rs.getInt("status") : null);
        x.setVES_EDINIC(rs.getObject("VES_EDINIC") != null ? rs.getDouble("VES_EDINIC") : null);
        x.setDESCRIPTION(rs.getString("DESCRIPTION"));
        x.setRAZM_IZMER(rs.getString("RAZM_IZMER"));
        x.setGr_descr(rs.getString("gr_descr"));
        return x;
    }
}