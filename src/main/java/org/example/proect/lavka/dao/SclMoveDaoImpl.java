package org.example.proect.lavka.dao;

import org.example.proect.lavka.dao.mapper.SclMoveMapper;
import org.example.proect.lavka.entity.SclMove;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
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
public class SclMoveDaoImpl implements SclMoveDao {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public SclMoveDaoImpl(@Qualifier("folioJdbcTemplate")JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SclMove> getMoveByGoodsAndData(String NamePredm, int id, String start, String end) {
        try {
            return jdbcTemplate.query("SELECT * FROM SCL_MOVE WHERE NAME_PREDM=? AND ID_SCLAD=? AND DATE_PREDM>=? AND DATE_PREDM<=?;",
                    new SclMoveMapper(), NamePredm, id, start, end);
        } catch (
                DataAccessException e) {
            return null;
        }

    }

    @Override
    public List<SclMove> getMoveByListOfGoodsAndData(List<String> namePredmList, List<Long> idList, String start, String end) {

        String idParams = String.join(",", idList.stream().map(String::valueOf).toList());
        String namePredmParams = String.join("','", namePredmList);
        String sqlQuery = "SELECT * FROM SCL_MOVE WHERE STND_UCHET!=0 AND NAME_PREDM IN ('" + namePredmParams + "') AND ID_SCLAD IN (" + idParams + ") AND DATE_PREDM>=? AND DATE_PREDM<=?;";

        //TODO maximum length of query?
        // String sqlQuery = "SELECT * FROM SCL_MOVE WHERE NAME_PREDM IN (" + namePredmParams + ") AND ID_SCLAD=? AND DATE_PREDM>=? AND DATE_PREDM<=?;";


        try {
            return jdbcTemplate.query(sqlQuery, new SclMoveMapper(), start, end);
        } catch (
                DataAccessException e) {
            return null;
        }
    }
}
