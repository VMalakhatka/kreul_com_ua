package org.example.proect.lavka.dao;

import org.example.proect.lavka.dao.mapper.AssembleMapper;
import org.example.proect.lavka.dto.AssembleDtoOut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AssembleDaoImp implements AssembleDao {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public AssembleDaoImp(@Qualifier("folioJdbcTemplate")JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AssembleDtoOut> getAssembleByGoodsList(List<String> namePredmList) {
        String namePredmParams = String.join("','", namePredmList);
        String sqlQuery = "SELECT ART,ART_R,KOL_R FROM ALL_RAZBORKA WHERE ART IN ('" + namePredmParams + "');";
//TODO maximum length of query?
        try {
            return jdbcTemplate.query(sqlQuery, new AssembleMapper());
        } catch (
                DataAccessException e) {
            return null;
        }
    }
}
