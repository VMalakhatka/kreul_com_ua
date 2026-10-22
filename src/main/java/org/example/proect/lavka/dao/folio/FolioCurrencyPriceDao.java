package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

@Repository
public class FolioCurrencyPriceDao {
    public record Input(String sku, String price, String currency) {}
    public record Row(int input, String table, String sku, int warehouse, Long id, String price, String currency) {}
    private final JdbcTemplate jdbc;
    public FolioCurrencyPriceDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public void preflight() {
        if (!"Paint_Ua".equals(jdbc.queryForObject("SELECT DB_NAME()", String.class))) throw new IllegalStateException("WRONG_DATABASE");
        // Fail closed if the columns or side effects have changed since the documented schema.
        var expected=Map.of("SCL_ARTC.COD_ARTIC","varchar:20", "SCL_ARTC.ID_SCLAD","int:4",
                "SCL_ARTC.CENA_VALT","float:8", "SCL_ARTC.COD_VALT","varchar:4",
                "SCL_PRIC.ID","int:4", "SCL_PRIC.COD_ARTIC","varchar:20", "SCL_PRIC.ID_SCLAD","int:4",
                "SCL_PRIC.NAME_PRICE","varchar:30", "SCL_PRIC.RUB_PRICE","float:8");
        var actual=new HashMap<String,String>();
        jdbc.query("SELECT o.name t,c.name n,TYPE_NAME(c.xtype) typ,c.length len FROM dbo.syscolumns c JOIN dbo.sysobjects o ON o.id=c.id WHERE o.id IN (OBJECT_ID('dbo.SCL_ARTC'),OBJECT_ID('dbo.SCL_PRIC'))",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> actual.put(rs.getString("t")+"."+rs.getString("n"),rs.getString("typ")+":"+rs.getInt("len")));
        if (!expected.entrySet().stream().allMatch(e->e.getValue().equals(actual.get(e.getKey())))) throw new IllegalStateException("SCHEMA_CHANGED");
        Integer triggers=jdbc.queryForObject("SELECT COUNT(*) FROM dbo.sysobjects WHERE xtype='TR' AND parent_obj IN (OBJECT_ID('dbo.SCL_ARTC'),OBJECT_ID('dbo.SCL_PRIC'))",Integer.class);
        if (triggers==null || triggers!=0) throw new IllegalStateException("SCHEMA_CHANGED");
    }
    private static String decimal(ResultSet rs,String key) throws SQLException {
        BigDecimal b=rs.getBigDecimal(key);return b==null?null:b.stripTrailingZeros().toPlainString();
    }
    public List<Row> snapshot(List<Input> inputs, boolean lock) {
        var rows=new ArrayList<Row>();
        for(int offset=0;offset<inputs.size();offset+=100) {
            int start=offset,end=Math.min(offset+100,inputs.size());
            String source=String.join(" UNION ALL ",Collections.nCopies(end-start,"SELECT CAST(? AS varchar(20)) sku, CAST(? AS int) n"));
            String hint=lock?" WITH (UPDLOCK,HOLDLOCK)":"";
            for(String table:List.of("SCL_ARTC","SCL_PRIC")) {
                boolean card=table.equals("SCL_ARTC");
                String sql="SELECT TOP 5001 w.n,t.COD_ARTIC,t.ID_SCLAD,"+(card?"CAST(NULL AS int) id,t.CENA_VALT price,t.COD_VALT currency":"t.ID id,t.RUB_PRICE price,CAST(NULL AS varchar(4)) currency")+
                    " FROM ("+source+") w JOIN dbo."+table+" t"+hint+" ON t.COD_ARTIC=w.sku"+
                    (card?"":" WHERE t.NAME_PRICE='ЯЯ_Р_ВАЛЮТ' AND t.ID_SCLAD=7")+" ORDER BY w.n,t.COD_ARTIC,t.ID_SCLAD"+(card?"":",t.ID");
                RowMapper<Row> mapper=(rs,n)->new Row(rs.getInt("n"),table,rs.getString("COD_ARTIC"),rs.getInt("ID_SCLAD"),card?null:rs.getLong("id"),decimal(rs,"price"),rs.getString("currency"));
                rows.addAll(jdbc.query(con->{var ps=con.prepareStatement(sql);ps.setQueryTimeout(45);int p=1;
                    for(int i=start;i<end;i++){ps.setString(p++,inputs.get(i).sku());ps.setInt(p++,i);}return ps;},mapper));
                if(rows.size()>5000) throw new IllegalArgumentException("TOO_MANY_TARGETS");
            }
        }
        rows.sort(Comparator.comparing(Row::table).thenComparing(Row::sku).thenComparingInt(Row::warehouse).thenComparing(r->r.id()==null?0L:r.id()).thenComparingInt(Row::input));
        // Detect different input spellings which SQL Server's collation resolves to one target.
        var targets=new HashSet<String>();
        for(Row r:rows) if(!targets.add(r.table()+"|"+(r.id()==null?r.sku()+"|"+r.warehouse():r.id()))) throw new IllegalArgumentException("OVERLAPPING_SKUS");
        return List.copyOf(rows);
    }
    public int updateCards(Input row) {
        return update("UPDATE dbo.SCL_ARTC SET CENA_VALT=?,COD_VALT=? WHERE COD_ARTIC=?",row,true);
    }
    public int updateRetail(Input row) {
        return update("UPDATE dbo.SCL_PRIC SET RUB_PRICE=? WHERE COD_ARTIC=? AND NAME_PRICE='ЯЯ_Р_ВАЛЮТ' AND ID_SCLAD=7",row,false);
    }
    private int update(String sql,Input row,boolean card) {
        return jdbc.update(con->{var ps=con.prepareStatement(sql);ps.setQueryTimeout(45);ps.setBigDecimal(1,new BigDecimal(row.price()));
            if(card){ps.setString(2,row.currency());ps.setString(3,row.sku());}else ps.setString(2,row.sku());return ps;});
    }
}
