package org.example.proect.lavka.dao.stock;

import org.example.proect.lavka.dto.stock.PriceRow;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

import java.util.*;

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
public class PriceDaoImpl implements PriceDao {


    private final NamedParameterJdbcTemplate namedJdbc;
    private final LavkaApiProperties props;

    public PriceDaoImpl(@Qualifier("folioNamedJdbc") NamedParameterJdbcTemplate namedJdbc,
                        LavkaApiProperties props) {
        this.namedJdbc = namedJdbc;
        this.props = props;
    }


    @Override
    public List<PriceRow> findLatestPrices(int stockId, List<String> skus, List<String> namePrices) {
        final String sql = """
        SELECT p.COD_ARTIC  AS sku,
               p.NAME_PRICE AS name_price,
               p.RUB_PRICE  AS rub_price,
               p.VALT_PRICE AS valt_price
        FROM dbo.SCL_PRIC p
        WHERE p.ID_SCLAD   = :stockId
          AND p.COD_ARTIC  IN (:skus)
          AND p.NAME_PRICE IN (:names)
    """;

        final int margin = 10;
        final int maxParams   = props.getMssqlMaxParams();
        final int fixedParams = 1; // stockId
        final int allowedSkus = Math.max(1, maxParams - fixedParams - namePrices.size() - margin);
        final int chunkSize   = Math.min(props.getSkuChunkSize(), allowedSkus);

        List<PriceRow> out = new ArrayList<>();
        for (List<String> skuChunk : com.google.common.collect.Lists.partition(skus, chunkSize)) {
            var params = new MapSqlParameterSource()
                    .addValue("stockId", stockId)
                    .addValue("skus",   skuChunk)
                    .addValue("names",  namePrices);

            out.addAll(namedJdbc.query(sql, params, (rs, i) -> new PriceRow(
                    rs.getString("sku"),
                    rs.getString("name_price"),
                    (rs.getObject("rub_price")  == null ? null : rs.getDouble("rub_price")),
                    (rs.getObject("valt_price") == null ? null : rs.getDouble("valt_price"))
            )));
        }
        return out;
    }

    // PriceDaoImpl.java (добавьте метод)
    @Override
    public Map<String, Double> findRetailPrices(int stockId, List<String> skus) {
        if (skus == null || skus.isEmpty()) return Map.of();

        List<String> normSkus = skus.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(s -> s.replace('\u2013', '-'))
                .toList();

        final String sql = """
        SELECT 
            REPLACE(LTRIM(RTRIM(a.COD_ARTIC)), N'–', N'-') AS sku,
            a.CENA_ARTIC AS retail_price
        FROM dbo.SCL_ARTC a
        WHERE a.ID_SCLAD = :stockId
          AND REPLACE(LTRIM(RTRIM(a.COD_ARTIC)), N'–', N'-') IN (:skus)
    """;

        final int margin = 10;
        final int maxParams   = props.getMssqlMaxParams();
        final int fixedParams = 1; // stockId
        final int allowedSkus = Math.max(1, maxParams - fixedParams - margin);
        final int chunkSize   = Math.min(props.getSkuChunkSize(), allowedSkus);

        Map<String, Double> out = new LinkedHashMap<>();
        for (String s : skus) out.put(s, null);

        for (List<String> chunk : com.google.common.collect.Lists.partition(normSkus, chunkSize)) {
            var params = new MapSqlParameterSource()
                    .addValue("stockId", stockId)
                    .addValue("skus", chunk);

            namedJdbc.query(sql, params, rs -> {
                String sku = rs.getString("sku");
                Double price = (rs.getObject("retail_price") == null ? null : rs.getDouble("retail_price"));
                out.put(sku, price);
            });
        }
        return out;
    }

}