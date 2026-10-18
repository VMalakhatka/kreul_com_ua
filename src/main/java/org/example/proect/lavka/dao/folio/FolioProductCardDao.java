package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Types;
import java.util.*;

/** Read-only equivalents of the two Access card queries; no Access code is executed. */
@Repository
public class FolioProductCardDao {
    public static final int MAX_ROWS = 50000;
    private final JdbcTemplate jdbc;
    public FolioProductCardDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Column(String key, String type) {}
    public record Data(List<Column> columns, List<List<String>> rows) {}
    public record Result(boolean ok, String sourceDatabase, String mode, Data data,
                         Boolean identical, Integer directRows, Integer viewRows, List<String> missingSkus) {}
    static final String DIRECT = """
SELECT TOP 50001
s.COD_ARTIC AS [Now_COD_ARTIC],
s.COD_ARTIC AS [COD_ARTIC],
c.ARTIC_BAZA AS [ARTIC_BAZA],
s.NAME_ARTIC AS [NAME_ARTIC],
s.DOP1_ARTIC AS [DOP1_ARTIC],
s.EDIN_IZMER AS [EDIN_IZMER],
s.EDN_V_UPAK AS [EDN_V_UPAK],
a.FASOVKA_KALM AS [FASOVKA_KALM],
s.DOP2_ARTIC AS [DOP2_ARTIC],
s.NGROUP_TVR AS [NGROUP_TVR],
s.NGROUP_TV2 AS [NGROUP_TV2],
s.NGROUP_TV3 AS [NGROUP_TV3],
s.NGROUP_TV4 AS [NGROUP_TV4],
s.NGROUP_TV5 AS [NGROUP_TV5],
s.NGROUP_TV6 AS [NGROUP_TV6],
s.COEF_BZNAL AS [COEF_BZNAL],
s.DEPARTAM AS [DEPARTAM],
s.TIP_TOVR AS [TIP_TOVR],
a.PR_CENA AS [PR_CENA],
s.CENA_VALT AS [CENA_VALT],
s.COD_VALT AS [COD_VALT],
p.COEF_PRICE AS [NACENKA_UA],
p1.RUB_PRICE AS [NACENKA_OPT_UA],
s.CENA_ARTIC AS [CENA_ARTIC],
a.COUNTRY AS [COUNTRY],
s.BALL2 AS [BALL2],
s.DOP3_ARTIC AS [SHTRIH],
s1.MAX_TVRZAP AS [MAX_KIOPT],
s2.MAX_TVRZAP AS [MAX_XR_KIOPT],
s3.MAX_TVRZAP AS [MAX_ODESS],
s6.MAX_TVRZAP AS [MAX_ODESS_HR],
s4.MAX_TVRZAP AS [MAX_KIEV],
s5.MAX_TVRZAP AS [MAX_KIEV_3],
s1.MIN_TVRZAP AS [MIN_KIOPT],
s3.MIN_TVRZAP AS [MIN_ODESS],
s6.MIN_TVRZAP AS [MIN_ODESS_HR],
s4.MIN_TVRZAP AS [MIN_KIEV],
s5.MIN_TVRZAP AS [MIN_KIEV_3],
s4.BALL5 AS [BALL5 k1],
s5.BALL5 AS [BALL5 k3],
s3.BALL5 AS [BALL5 od],
s.BALL1 AS [BALL1],
s.BALL4 AS [BALL4],
s.DLINA_ART AS [DLINA],
s.SHIRIN_ART AS [SHIRINA],
s.VYSOTA_ART AS [VISOTA],
s.BALL3 AS [VEC],
s1.OKDP_ARTIC AS [ADREC_KIO],
s2.OKDP_ARTIC AS [ADREC_HR],
s1.SCHET_ART AS [SCHET_ART],
a.tr_transf AS [tr_trans],
a.S25 AS [Art_dop],
a.S50 AS [S50],
a.DESCRIPTION AS [DESCRIPTION] FROM dbo.SCL_ARTC s
LEFT JOIN dbo.ALL_ARTC a ON s.COD_ARTIC=a.COD_ARTIC
LEFT JOIN dbo.ALL_CENA c ON s.COD_ARTIC=c.ARTIC
LEFT JOIN dbo.SCL_PRIC p ON s.COD_ARTIC=p.COD_ARTIC
LEFT JOIN dbo.SCL_PRIC p1 ON s.COD_ARTIC=p1.COD_ARTIC
LEFT JOIN dbo.SCL_ARTC s1 ON s.COD_ARTIC=s1.COD_ARTIC
LEFT JOIN dbo.SCL_ARTC s2 ON s.COD_ARTIC=s2.COD_ARTIC
LEFT JOIN dbo.SCL_ARTC s3 ON s.COD_ARTIC=s3.COD_ARTIC
LEFT JOIN dbo.SCL_ARTC s4 ON s.COD_ARTIC=s4.COD_ARTIC
LEFT JOIN dbo.SCL_ARTC s5 ON s.COD_ARTIC=s5.COD_ARTIC
LEFT JOIN dbo.SCL_ARTC s6 ON s.COD_ARTIC=s6.COD_ARTIC
WHERE s.ID_SCLAD=7 AND p.ID_SCLAD=7 AND p1.ID_SCLAD=7 AND p.NAME_PRICE='НАЦЕНКА' AND p1.NAME_PRICE='НАЦЕН_ОПТ' AND s1.ID_SCLAD=7 AND s2.ID_SCLAD=12 AND s3.ID_SCLAD=5 AND s4.ID_SCLAD=1 AND s5.ID_SCLAD=3 AND s6.ID_SCLAD=15
""";
    static final String VIEW = """
SELECT TOP 50001 v.COD_ARTIC AS [Now_COD_ARTIC], v.[COD_ARTIC], v.[ARTIC_BAZA], v.[NAME_ARTIC], v.[DOP1_ARTIC], v.[EDIN_IZMER], v.[EDN_V_UPAK], v.[FASOVKA_KALM], v.[DOP2_ARTIC], v.[NGROUP_TVR], v.[NGROUP_TV2], v.[NGROUP_TV3], v.[NGROUP_TV4], v.[NGROUP_TV5], v.[NGROUP_TV6], v.[COEF_BZNAL], v.[DEPARTAM], v.[TIP_TOVR], v.[PR_CENA], v.[CENA_VALT], v.[COD_VALT], v.[NACENKA_UA], v.[NACENKA_OPT_UA], v.[CENA_ARTIC], v.[COUNTRY], v.[BALL2], v.[SHTRIH], v.[MAX_KIOPT], v.[MAX_XR_KIOPT], v.[MAX_ODESS], v.[MAX_ODESS_HR], v.[MAX_KIEV], v.[MAX_KIEV_3], v.[MIN_KIOPT], v.[MIN_ODESS], v.[MIN_ODESS_HR], v.[MIN_KIEV], v.[MIN_KIEV_3], v.[BALL5 k1], v.[BALL5 k3], v.[BALL5 od], v.[BALL1], v.[BALL4], v.[DLINA], v.[SHIRINA], v.[VISOTA], v.[VEC], v.[ADREC_KIO], v.[ADREC_HR], v.[SCHET_ART], v.[tr_trans], v.[Art_dop], v.[S50], v.[DESCRIPTION] FROM dbo.card_tov v WHERE 1=1
""";

    @Transactional(transactionManager="mssqlTransactionManager", readOnly=true, isolation=Isolation.SERIALIZABLE, timeout=60)
    public Result read(String mode, List<String> skus) {
        if (!Set.of("direct", "view", "compare").contains(mode) || skus == null || skus.size() > 500
                || skus.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 100 || s.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("Invalid card selection");
        String database = jdbc.queryForObject("SELECT DB_NAME()", String.class);
        if (!"Paint_Ua".equalsIgnoreCase(database == null ? "" : database.trim()))
            throw new IllegalArgumentException("Product card source database mismatch");
        Data direct = mode.equals("view") ? null : query(false, skus);
        Data view = mode.equals("direct") ? null : query(true, skus);
        Boolean identical = direct != null && view != null ? equivalent(direct, view) : null;
        Data data = mode.equals("view") ? view : direct;
        Set<String> found = new HashSet<>();
        for (var row : data.rows()) if (row.get(1) != null) found.add(row.get(1).stripTrailing().toUpperCase(Locale.ROOT));
        var missing = skus.stream().filter(s -> !found.contains(s.stripTrailing().toUpperCase(Locale.ROOT))).toList();
        return new Result(true, "Paint_Ua", mode, data, identical, direct == null ? null : direct.rows().size(),
                view == null ? null : view.rows().size(), missing);
    }

    private Data query(boolean view, List<String> skus) {
        String alias = view ? "v" : "s";
        String sql = (view ? VIEW : DIRECT) + (skus.isEmpty() ? "" : " AND " + alias + ".COD_ARTIC IN ("
                + String.join(",", Collections.nCopies(skus.size(), "CAST(? AS varchar(100))")) + ")")
                + " ORDER BY " + alias + ".COD_ARTIC";
        return jdbc.query(c -> {
            var p = c.prepareStatement(sql); p.setQueryTimeout(45);
            for (int i=0; i<skus.size(); i++) p.setString(i+1, skus.get(i));
            return p;
        }, (ResultSetExtractor<Data>) rs -> {
            var columns = new ArrayList<Column>(); var rows = new ArrayList<List<String>>();
            var meta = rs.getMetaData();
            for (int i=1; i<=meta.getColumnCount(); i++) {
                boolean numeric = switch (meta.getColumnType(i)) {
                    case Types.BIT, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                         Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> true;
                    default -> false;
                };
                columns.add(new Column(meta.getColumnLabel(i), numeric ? "number" : "text"));
            }
            while (rs.next()) {
                if (rows.size() == MAX_ROWS) throw new IllegalArgumentException("Product card row limit exceeded");
                var row = new ArrayList<String>();
                for (int i=1; i<=columns.size(); i++) {
                    if (columns.get(i-1).type().equals("number")) {
                        var number = rs.getBigDecimal(i); row.add(number == null ? null : number.toPlainString());
                    } else row.add(rs.getString(i));
                }
                rows.add(row);
            }
            return new Data(columns, rows);
        });
    }

    /** Compare multisets, preserving duplicates and NULL versus empty text. */
    static boolean equivalent(Data a, Data b) {
        if (!a.columns().equals(b.columns())) return false;
        return counts(a).equals(counts(b));
    }
    private static Map<List<String>, Integer> counts(Data data) {
        Map<List<String>, Integer> result = new HashMap<>();
        for (var row : data.rows()) {
            var normalized = new ArrayList<String>(row);
            for (int i=0; i<row.size(); i++) if (row.get(i) != null && data.columns().get(i).type().equals("number"))
                normalized.set(i, new java.math.BigDecimal(row.get(i)).stripTrailingZeros().toPlainString());
            result.merge(normalized, 1, Integer::sum);
        }
        return result;
    }
}
