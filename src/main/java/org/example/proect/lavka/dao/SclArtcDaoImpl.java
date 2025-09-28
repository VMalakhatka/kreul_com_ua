package org.example.proect.lavka.dao;

import org.example.proect.lavka.dao.mapper.SclArtcMapper;
import org.example.proect.lavka.dao.mapper.SclRestMapper;
import org.example.proect.lavka.dao.mapper.StockParamMapper;
import org.example.proect.lavka.dto.RestDtoOut;
import org.example.proect.lavka.dto.StockParamDtoOut;
import org.example.proect.lavka.dto.stock.StockRow;
import org.example.proect.lavka.entity.SclArtc;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;


import com.google.common.collect.Lists;

@Component
public class SclArtcDaoImpl implements SclArtcDao {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final LavkaApiProperties props;


    @Autowired
    public SclArtcDaoImpl(@Qualifier("folioJdbcTemplate")JdbcTemplate jdbcTemplate
            ,@Qualifier("folioNamedJdbc") NamedParameterJdbcTemplate namedJdbc
            ,LavkaApiProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.props = props;
    }
    @Override
    public List<String> findSkusWithMovement(Set<Integer> scladIds,
                                             Instant from,
                                             Instant to,
                                             int limit,
                                             int offset) {
        int lim = Math.max(0, limit);
        int off = Math.max(0, offset);

        // ВСТАВЛЯЕМ ЧИСЛА В SQL (TOP не параметризуем)
        final String sql =
                "SELECT TOP " + lim + " sku\n" +
                        "FROM (\n" +
                        "    SELECT DISTINCT m.NAME_PREDM AS sku\n" +
                        "    FROM dbo.SCL_MOVE m\n" +
                        "    WHERE m.ID_SCLAD IN (:sclads)\n" +
                        "      AND m.DATE_PREDM >= :from\n" +
                        "      AND m.DATE_PREDM <  :to\n" +
                        "      AND m.NAME_PREDM NOT IN (\n" +
                        "          SELECT DISTINCT TOP " + off + " m2.NAME_PREDM\n" +
                        "          FROM dbo.SCL_MOVE m2\n" +
                        "          WHERE m2.ID_SCLAD IN (:sclads)\n" +
                        "            AND m2.DATE_PREDM >= :from\n" +
                        "            AND m2.DATE_PREDM <  :to\n" +
                        "          ORDER BY m2.NAME_PREDM\n" +
                        "      )\n" +
                        ") x\n" +
                        "ORDER BY x.sku";

        var params = new MapSqlParameterSource()
                .addValue("sclads", new ArrayList<>(scladIds))
                .addValue("from", java.sql.Timestamp.from(from))
                .addValue("to",   java.sql.Timestamp.from(to));

        return namedJdbc.query(sql, params, (rs, i) -> rs.getString("sku"));
    }

    // org.example.proect.lavka.dao.SclArtcDaoImpl
    @Override
        public List<StockRow> findFreeAll(Set<Integer> scladIds) {
            final String sql = """
            SELECT a.COD_ARTIC AS sku,
                   SUM(ISNULL(a.REZ_KOLCH,0)) AS free_qty
            FROM dbo.SCL_ARTC a
            WHERE a.ID_SCLAD IN (:sclads)
            GROUP BY a.COD_ARTIC
            ORDER BY a.COD_ARTIC
        """;

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("sclads", new ArrayList<>(scladIds));

            return namedJdbc.query(sql, params, (rs, i) ->
                    new StockRow(rs.getString("sku"), rs.getInt("free_qty")));
        }

        @Override
        public List<StockRow> findFreeBySkus(Set<Integer> scladIds, List<String> skus) {
            final String sql = """
        SELECT a.COD_ARTIC AS sku,
               SUM(ISNULL(a.REZ_KOLCH,0)) AS free_qty
        FROM dbo.SCL_ARTC a
        WHERE a.ID_SCLAD IN (:sclads)
          AND a.COD_ARTIC IN (:skus)
        GROUP BY a.COD_ARTIC
        ORDER BY a.COD_ARTIC
    """;

            List<StockRow> out = new ArrayList<>();

            // лимит параметров MSSQL: 2100 (конфигурируемый)
            // фактическое число параметров в запросе = |sclads| + |skus| + небольшой запас
            final int margin = 10;
            final int allowedSkusPerQuery = Math.max(1,
                    props.getMssqlMaxParams() - scladIds.size() - margin);

            // берём минимальный размер чанка между твоей "делёжкой" и лимитом БД
            final int chunkSize = Math.min(props.getSkuChunkSize(), allowedSkusPerQuery);

            for (List<String> chunk : Lists.partition(skus, chunkSize)) {
                MapSqlParameterSource params = new MapSqlParameterSource()
                        .addValue("sclads", new ArrayList<>(scladIds))
                        .addValue("skus", chunk);

                out.addAll(namedJdbc.query(sql, params, (rs, i) ->
                        new StockRow(rs.getString("sku"), rs.getInt("free_qty"))));
            }
            return out;
        }


    @Override
    public List<SclArtc> getAllBySupplierAndStockId(String supplier, long idStock) {
        try {
            return jdbcTemplate.query("SELECT COD_ARTIC,NAME_ARTIC,CENA_VALT,COD_VALT,KON_KOLCH,REZ_KOLCH," +
                            "EDIN_IZMER,EDN_V_UPAK,DOP2_ARTIC,DOP3_ARTIC,MIN_TVRZAP,MAX_TVRZAP,ID_SCLAD,BALL1," +
                            "BALL2,BALL4,BALL5,TIP_TOVR  FROM SCL_ARTC WHERE DOP2_ARTIC=? AND ID_SCLAD=?;",
                    new SclArtcMapper(), supplier, idStock);
        } catch (DataAccessException e) {
            return null;
        }
    }

    @Override
    public List<RestDtoOut> getRestByGoodsListAndStockList(List<String> namePredmList, List<Long> idList) {
        final String sql = """
        SELECT COD_ARTIC, ID_SCLAD, REZ_KOLCH, KON_KOLCH
        FROM SCL_ARTC
        WHERE COD_ARTIC IN (:skus)
          AND ID_SCLAD  IN (:ids)
    """;

        List<RestDtoOut> out = new ArrayList<>();

        // лимит по параметрам = ids + skus + запас
        final int margin = 10;
        final int allowedSkusPerQuery = Math.max(1,
                props.getMssqlMaxParams() - idList.size() - margin);

        final int chunkSize = Math.min(props.getSkuChunkSize(), allowedSkusPerQuery);

        for (List<String> chunk : Lists.partition(namePredmList, chunkSize)) {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("skus", chunk)
                    .addValue("ids", idList);

            out.addAll(namedJdbc.query(sql, params, new SclRestMapper()));
        }
        return out;
    }

    @Override
    public List<StockParamDtoOut> getStockParamByGoodsListAndStockList(List<String> namePredmList, List<Long> idList) {
        final String sql = """
        SELECT COD_ARTIC, ID_SCLAD, MIN_TVRZAP, MAX_TVRZAP, TIP_TOVR, BALL5
        FROM SCL_ARTC
        WHERE COD_ARTIC IN (:skus)
          AND ID_SCLAD  IN (:ids)
    """;

        List<StockParamDtoOut> out = new ArrayList<>();

        final int margin = 10;
        final int allowedSkusPerQuery = Math.max(1,
                props.getMssqlMaxParams() - idList.size() - margin);

        final int chunkSize = Math.min(props.getSkuChunkSize(), allowedSkusPerQuery);

        for (List<String> chunk : Lists.partition(namePredmList, chunkSize)) {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("skus", chunk)
                    .addValue("ids", idList);

            out.addAll(namedJdbc.query(sql, params, new StockParamMapper()));
        }
        return out;
    }

    @Override
    public List<SclArtc> getGoodsByNumDoc(long numDoc) {
        try {
            return jdbcTemplate.query("SELECT SCL_ARTC.COD_ARTIC, SCL_ARTC.NAME_ARTIC, SCL_ARTC.CENA_VALT, SCL_ARTC.COD_VALT, SCL_ARTC.KON_KOLCH, SCL_ARTC.REZ_KOLCH, SCL_ARTC.EDIN_IZMER,\n" +
                            "       SCL_ARTC.EDN_V_UPAK, SCL_ARTC.DOP2_ARTIC, SCL_ARTC.DOP3_ARTIC, SCL_ARTC.MIN_TVRZAP, SCL_ARTC.MAX_TVRZAP, SCL_ARTC.ID_SCLAD, SCL_ARTC.BALL1,\n" +
                            "       SCL_ARTC.BALL2, SCL_ARTC.BALL4, SCL_ARTC.BALL5, SCL_ARTC.TIP_TOVR\n" +
                            "FROM SCL_MOVE INNER JOIN SCL_ARTC ON (SCL_MOVE.ID_SCLAD = SCL_ARTC.ID_SCLAD) AND (SCL_MOVE.NAME_PREDM = SCL_ARTC.COD_ARTIC)\n" +
                            "WHERE (((SCL_MOVE.UNICUM_NUM)=?));",
                    new SclArtcMapper(), numDoc);
        } catch (DataAccessException e) {
            return null;
        }
    }
}

