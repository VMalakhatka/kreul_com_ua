package org.example.proect.lavka.dao;

import org.example.proect.lavka.dao.mapper.AssembleMapper;
import org.example.proect.lavka.dto.AssembleDtoOut;
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
