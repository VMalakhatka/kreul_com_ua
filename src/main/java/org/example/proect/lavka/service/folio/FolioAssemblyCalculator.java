package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioManagerDao.Table;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;

/** Access scratch tables become connection-local tempdb tables. No Folio DML. */
@Service
public class FolioAssemblyCalculator {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public FolioAssemblyCalculator(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc,
        @Qualifier("mssqlTransactionManager") PlatformTransactionManager manager) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);tx.setTimeout(120);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    // Access Round uses ties-to-even; SQL Server ROUND alone uses ties-away-from-zero.
    static String roundEven(String value,int scale) {
        String factor=scale==0?"1.0":"100.0", x="ABS(("+value+")*"+factor+")";
        return "(CASE WHEN "+x+"-FLOOR("+x+")=0.5 THEN SIGN("+value+")*(FLOOR("+x+")+CASE WHEN FLOOR("+x+")/2.0=FLOOR(FLOOR("+x+")/2.0) THEN 0 ELSE 1 END)/"+factor+" ELSE ROUND("+value+","+scale+") END)";
    }
    public Map<String,Table> calculate(List<Map<String,String>> input) {
        return tx.execute(ignored->jdbc.execute((ConnectionCallback<Map<String,Table>>)c->calculate(c,input)));
    }
    private Map<String,Table> calculate(Connection c,List<Map<String,String>> input) throws SQLException {
        try {
            execute(c,"CREATE TABLE #fm_input (n int IDENTITY PRIMARY KEY,ARTIC_roditel varchar(20) COLLATE database_default NULL,ARTIC_rebenok varchar(20) COLLATE database_default NULL,KOL_rod_razob float NULL,kol_reb_pol smallint NULL,CENA_REB real NULL,SLOG int NOT NULL)");
            execute(c,"CREATE TABLE #fm_simple (n int IDENTITY PRIMARY KEY,ARTIC varchar(20) COLLATE database_default NULL,KOL_R float NULL,PRICE real NULL,ARTIC_REB varchar(20) COLLATE database_default NULL,cborka varchar(10) COLLATE database_default NULL)");
            execute(c,"CREATE TABLE #fm_assembly (n int IDENTITY PRIMARY KEY,ARTIC varchar(20) COLLATE database_default NULL,KOL_R float NULL,PRICE real NULL,ARTIC_REB varchar(20) COLLATE database_default NULL,cborka varchar(10) COLLATE database_default NULL)");
            try(var p=c.prepareStatement("INSERT INTO #fm_input (ARTIC_roditel,ARTIC_rebenok,KOL_rod_razob,kol_reb_pol,CENA_REB,SLOG) VALUES (?,?,?,?,?,?)")) {
                p.setQueryTimeout(30);
                for(var row:input){int i=1;for(String key:FolioManagerOperation.ASSEMBLY.columns()) {
                    String v=row.get(key);if(i<=2)p.setString(i,v);else if(v==null)p.setNull(i,Types.DOUBLE);else p.setDouble(i,Double.parseDouble(v));i++;
                }p.addBatch();}p.executeBatch();
            }
            String join12=" FROM #fm_input i LEFT JOIN dbo.ALL_RAZBORKA r ON i.ARTIC_roditel=r.ART LEFT JOIN dbo.SCL_PRIC p ON i.ARTIC_roditel=p.COD_ARTIC LEFT JOIN dbo.SCL_PRIC d ON i.ARTIC_roditel=d.COD_ARTIC LEFT JOIN dbo.SCL_ARTC a ON i.ARTIC_roditel=a.COD_ARTIC WHERE i.ARTIC_rebenok IS NULL AND d.NAME_PRICE='Я_ЦЕНА_ДОЛ' AND p.NAME_PRICE='Я_КУРС_ГРН' AND p.ID_SCLAD=7 AND d.ID_SCLAD=7 AND a.ID_SCLAD=7";
            unambiguous(c,"SELECT i.n"+join12+" GROUP BY i.n HAVING COUNT(*)>1","ASSEMBLY_12_AMBIGUOUS");
            execute(c,"UPDATE i SET ARTIC_rebenok=r.ART_R,CENA_REB=p.RUB_PRICE*d.RUB_PRICE*CAST(REPLACE(a.SCHET_ART,',','.') AS float),kol_reb_pol="+roundEven("i.KOL_rod_razob*r.KOL_R",0)+join12);
            execute(c,"UPDATE i SET SLOG=1 FROM #fm_input i JOIN dbo.ALL_RAZBORKA_SLOJ r ON i.ARTIC_rebenok=r.ARTIC_REB WHERE i.ARTIC_rebenok IS NOT NULL");
            // Steps 14/3 delete local intermediate rows: each request starts with empty temp tables.
            execute(c,"INSERT INTO #fm_simple (ARTIC,KOL_R,PRICE,ARTIC_REB,cborka) SELECT ARTIC_rebenok,kol_reb_pol,CAST(CENA_REB AS float)/kol_reb_pol*KOL_rod_razob,ARTIC_rebenok,'WWORK' FROM #fm_input WHERE SLOG=0");
            execute(c,"INSERT INTO #fm_simple (ARTIC,KOL_R,PRICE,ARTIC_REB,cborka) SELECT ARTIC_roditel,-KOL_rod_razob,CENA_REB,ARTIC_rebenok,'WWORK' FROM #fm_input WHERE SLOG=0");
            var outputs=new LinkedHashMap<String,Table>();
            outputs.put("ASSEMBLY_18",read(c,"SELECT ARTIC,KOL_R,PRICE,ARTIC_REB,cborka FROM #fm_simple ORDER BY ARTIC_REB"));
            String join2=" FROM #fm_input i JOIN dbo.ALL_RAZBORKA_SLOJ r ON i.ARTIC_roditel=r.ARTIC_ROD AND i.ARTIC_rebenok=r.ARTIC_REB WHERE i.ARTIC_roditel IS NOT NULL AND i.SLOG=1";
            unambiguous(c,"SELECT i.n"+join2+" GROUP BY i.n HAVING COUNT(*)>1","ASSEMBLY_2_AMBIGUOUS");
            execute(c,"UPDATE i SET kol_reb_pol="+roundEven("i.KOL_rod_razob/r.KOL_R",0)+join2);
            execute(c,"INSERT INTO #fm_assembly (ARTIC,KOL_R,PRICE,ARTIC_REB,cborka) SELECT TOP 5001 r.ARTIC_ROD,-"+roundEven("i.kol_reb_pol*r.KOL_R",2)+","+roundEven("r.DOL_CEN_UA/r.KOL_R*p.RUB_PRICE",2)+",i.ARTIC_rebenok,'multi' FROM #fm_input i LEFT JOIN dbo.ALL_RAZBORKA_SLOJ r ON i.ARTIC_rebenok=r.ARTIC_REB LEFT JOIN dbo.SCL_PRIC p ON i.ARTIC_rebenok=p.COD_ARTIC WHERE p.ID_SCLAD=7 AND p.NAME_PRICE='Я_КУРС_ГРН' AND i.SLOG=1");
            unambiguous(c,"SELECT COUNT(*) FROM #fm_assembly HAVING COUNT(*)>5000","TOO_MANY_RESULTS");
            execute(c,"INSERT INTO #fm_assembly (ARTIC,KOL_R,PRICE,cborka) SELECT ARTIC_rebenok,kol_reb_pol,0,'multi' FROM #fm_input WHERE SLOG=1");
            // Owner-confirmed price rule: sum every consumed component once per produced row.
            // Duplicate output children are rejected rather than cross-multiplying component costs.
            unambiguous(c,"SELECT ARTIC_rebenok FROM #fm_input WHERE SLOG=1 GROUP BY ARTIC_rebenok HAVING COUNT(*)>1","ASSEMBLY_DUPLICATE_CHILD");
            execute(c,"UPDATE t SET PRICE=-v.total/t.KOL_R FROM #fm_assembly t JOIN (SELECT ARTIC_REB,CASE WHEN COUNT(*)<>COUNT(PRICE*KOL_R) THEN NULL ELSE SUM(PRICE*KOL_R) END total FROM #fm_assembly WHERE ARTIC_REB IS NOT NULL GROUP BY ARTIC_REB) v ON t.ARTIC=v.ARTIC_REB WHERE t.ARTIC_REB IS NULL");
            execute(c,"UPDATE #fm_assembly SET ARTIC_REB=ARTIC WHERE ARTIC_REB IS NULL");
            execute(c,"UPDATE s SET cborka='ПРОСТАЯ' FROM dbo.ALL_RAZBORKA r LEFT JOIN #fm_assembly s ON r.ART_R=s.ARTIC_REB WHERE s.cborka='multi'");
            outputs.put("ASSEMBLY_9",read(c,"SELECT ARTIC,KOL_R,PRICE,cborka,ARTIC_REB FROM #fm_assembly WHERE cborka='multi' ORDER BY ARTIC_REB"));
            outputs.put("ASSEMBLY_91",read(c,"SELECT ARTIC,KOL_R,PRICE,cborka,ARTIC_REB FROM #fm_assembly WHERE cborka='ПРОСТАЯ' ORDER BY ARTIC_REB"));
            outputs.put("ASSEMBLY_INPUT",read(c,"SELECT ARTIC_roditel,ARTIC_rebenok,KOL_rod_razob,kol_reb_pol,CENA_REB,SLOG FROM #fm_input ORDER BY n"));
            return outputs;
        } finally {
            for(String table:List.of("#fm_assembly","#fm_simple","#fm_input"))execute(c,"IF OBJECT_ID('tempdb.."+table+"') IS NOT NULL DROP TABLE "+table);
        }
    }
    private static void unambiguous(Connection c,String sql,String error)throws SQLException {
        try(var s=c.createStatement()){s.setQueryTimeout(30);try(var rs=s.executeQuery(sql)){if(rs.next())throw new IllegalArgumentException(error);}}
    }
    private static void execute(Connection c,String sql)throws SQLException {
        try(var s=c.createStatement()){s.setQueryTimeout(30);s.executeUpdate(sql);}
    }
    private static Table read(Connection c,String sql)throws SQLException {
        try(var s=c.createStatement()){s.setQueryTimeout(30);s.setMaxRows(5001);try(var rs=s.executeQuery(sql)) {
            var cols=new ArrayList<String>();var rows=new ArrayList<List<String>>();var meta=rs.getMetaData();
            for(int i=1;i<=meta.getColumnCount();i++)cols.add(meta.getColumnLabel(i));
            while(rs.next()){var row=new ArrayList<String>();for(int i=1;i<=cols.size();i++)row.add(rs.getString(i));rows.add(row);if(rows.size()>5000)throw new IllegalArgumentException("TOO_MANY_RESULTS");}
            return new Table(cols,rows);
        }}
    }
}
