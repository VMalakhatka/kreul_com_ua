package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.dto.folio.FolioProfitScopeCatalog.Warehouse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class FolioProfitScopeCatalogDao {
    private final JdbcTemplate jdbc;
    public FolioProfitScopeCatalogDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public List<Warehouse> warehouses() {
        return query("SELECT ID_SCLAD, NAME_SCLAD FROM dbo.SCLAD_R WITH (NOLOCK) ORDER BY ID_SCLAD",
                (rs,i)->new Warehouse(rs.getInt(1),rs.getString(2)==null?"":rs.getString(2).trim()));
    }
    public List<List<String>> groupPaths() {
        // Binary grouping preserves case-distinct paths despite the legacy database CI collation.
        // Convert back on the server so jTDS handles the database code page.
        return query("""
                SELECT CONVERT(VARCHAR(255),G1), CONVERT(VARCHAR(255),G2),
                       CONVERT(VARCHAR(255),G3), CONVERT(VARCHAR(255),G4),
                       CONVERT(VARCHAR(255),G5), CONVERT(VARCHAR(255),G6)
                FROM (SELECT DISTINCT
                  CONVERT(VARBINARY(255), LTRIM(RTRIM(ISNULL(NGROUP_TVR,'')))) AS G1,
                  CONVERT(VARBINARY(255), LTRIM(RTRIM(ISNULL(NGROUP_TV2,'')))) AS G2,
                  CONVERT(VARBINARY(255), LTRIM(RTRIM(ISNULL(NGROUP_TV3,'')))) AS G3,
                  CONVERT(VARBINARY(255), LTRIM(RTRIM(ISNULL(NGROUP_TV4,'')))) AS G4,
                  CONVERT(VARBINARY(255), LTRIM(RTRIM(ISNULL(NGROUP_TV5,'')))) AS G5,
                  CONVERT(VARBINARY(255), LTRIM(RTRIM(ISNULL(NGROUP_TV6,'')))) AS G6
                FROM dbo.SCL_ARTC WITH (NOLOCK)) paths
                ORDER BY G1,G2,G3,G4,G5,G6
                """,(rs,i)->{
                    var path=new ArrayList<String>();
                    for(int level=1;level<=6;level++)
                        path.add(rs.getString(level));
                    while(!path.isEmpty()&&path.get(path.size()-1).isEmpty()) path.remove(path.size()-1);
                    return List.copyOf(path);
                });
    }
    private <T> List<T> query(String sql,RowMapper<T> mapper) {
        return jdbc.query(sql,ps->ps.setQueryTimeout(FolioProfitReadBudget.remainingQuerySeconds()),mapper);
    }
}
