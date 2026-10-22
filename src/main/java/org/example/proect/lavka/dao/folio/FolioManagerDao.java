package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.service.folio.FolioManagerOperation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.util.*;

@Repository
public class FolioManagerDao {
    public record Target(int input,String sku,Integer warehouse,Map<String,String> values) {}
    public record Table(List<String> columns,List<List<String>> rows) {}
    private final JdbcTemplate jdbc;
    public FolioManagerDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void preflight(FolioManagerOperation op) {
        if(!"Paint_Ua".equals(jdbc.queryForObject("SELECT DB_NAME()",String.class)))throw new IllegalStateException("WRONG_DATABASE");
        if(!op.writes())return;
        var expected=new HashMap<String,String>();expected.put("COD_ARTIC","varchar:20");
        if(!op.table.equals("ALL_ARTC"))expected.put("ID_SCLAD","int:4");
        for(int i=0;i<op.fields.size();i++)expected.put(op.fields.get(i),"varchar:"+op.lengths.get(i));
        var actual=new HashMap<String,String>();
        jdbc.query("SELECT name,TYPE_NAME(xtype) typ,length len FROM dbo.syscolumns WHERE id=OBJECT_ID('dbo."+op.table+"')",(RowCallbackHandler)rs->actual.put(rs.getString("name"),rs.getString("typ")+":"+rs.getInt("len")));
        if(!actual.entrySet().containsAll(expected.entrySet()))throw new IllegalStateException("SCHEMA_CHANGED");
        Integer triggers=jdbc.queryForObject("SELECT COUNT(*) FROM dbo.sysobjects WHERE xtype='TR' AND parent_obj=OBJECT_ID('dbo."+op.table+"')",Integer.class);
        if(triggers==null||triggers!=0)throw new IllegalStateException("TRIGGER_CHANGED");
    }
    public List<Target> snapshot(FolioManagerOperation op,List<Map<String,String>> inputs,boolean lock) {
        var rows=new ArrayList<Target>();var targets=new HashSet<String>();
        for(int i=0;i<inputs.size();i++) {
            final int n=i;
            String sql="SELECT TOP 5001 COD_ARTIC,"+(op.table.equals("ALL_ARTC")?"CAST(NULL AS int)":"ID_SCLAD")+" warehouse,"+String.join(",",op.fields)+" FROM dbo."+op.table+(lock?" WITH (UPDLOCK,HOLDLOCK)":"")+" WHERE COD_ARTIC=? ORDER BY COD_ARTIC,warehouse";
            rows.addAll(query(sql,Collections.singletonList(inputs.get(i).get(op.key)),(rs,k)->{
                var values=new LinkedHashMap<String,String>();for(String f:op.fields)values.put(f,rs.getString(f));
                return new Target(n,rs.getString("COD_ARTIC"),(Integer)rs.getObject("warehouse"),Collections.unmodifiableMap(values));
            }));
            if(rows.size()>5000)throw new IllegalArgumentException("TOO_MANY_TARGETS");
        }
        for(var r:rows)if(!targets.add(r.sku()+"|"+r.warehouse()))throw new IllegalArgumentException("OVERLAPPING_ARTICLES");
        return List.copyOf(rows);
    }
    public int update(FolioManagerOperation op,Map<String,String> row) {
        var args=new ArrayList<String>();for(String f:op.fields)args.add(row.get(f));args.add(row.get(op.key));
        return change("UPDATE dbo."+op.table+" SET "+String.join(",",op.fields.stream().map(f->f+"=?").toList())+" WHERE COD_ARTIC=?",args);
    }
    public int insertDescription(Map<String,String> row) {
        return change("INSERT INTO dbo.ALL_ARTC (DESCRIPTION,COD_ARTIC) VALUES (?,?)",Arrays.asList(row.get("DESCRIPTION"),row.get("COD_ARTIC")));
    }
    private int change(String sql,List<String> args) {
        return jdbc.update(c->{var p=c.prepareStatement(sql);p.setQueryTimeout(45);for(int i=0;i<args.size();i++)p.setString(i+1,args.get(i));return p;});
    }
    private <T> List<T> query(String sql,List<String> args,RowMapper<T> mapper) {
        return jdbc.query(c->{var p=c.prepareStatement(sql);p.setQueryTimeout(45);for(int i=0;i<args.size();i++)p.setString(i+1,args.get(i));return p;},mapper);
    }
    public Table base(List<Map<String,String>> inputs) {
        var rows=new ArrayList<List<String>>();
        for(var input:inputs) {
            var found=query("SELECT TOP 5001 t.ARTIC,t.ARTIC_BAZA FROM (SELECT CAST(? AS varchar(20)) ARTIC_BAZA) i LEFT JOIN dbo.ALL_CENA t ON i.ARTIC_BAZA=t.ARTIC_BAZA",Collections.singletonList(input.get("ARTIC_BAZA")),(rs,n)->Arrays.asList(rs.getString(1),rs.getString(2)));
            rows.addAll(found);if(rows.size()>5000)throw new IllegalArgumentException("TOO_MANY_RESULTS");
        }
        return new Table(List.of("ARTIC","ARTIC_BAZA"),rows);
    }
}
