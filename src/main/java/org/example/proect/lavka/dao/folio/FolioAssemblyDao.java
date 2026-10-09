package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.service.folio.FolioAssemblyGraph;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.PreparedStatement;
import java.util.*;

@Repository
public class FolioAssemblyDao {
    private final JdbcTemplate jdbc;
    public FolioAssemblyDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(transactionManager = "mssqlTransactionManager", readOnly = true, timeout = 60,
            isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public FolioAssemblyGraph.Graph read(String database, List<Integer> warehouses, List<String> roots) {
        String actual = jdbc.query(c -> { var p = c.prepareStatement("SELECT DB_NAME()"); p.setQueryTimeout(30); return p; },
                (org.springframework.jdbc.core.ResultSetExtractor<String>) rs -> rs.next() ? rs.getString(1) : null);
        if (!database.equalsIgnoreCase(actual == null ? "" : actual.trim()))
            throw new IllegalArgumentException("Assembly source database mismatch");
        List<FolioAssemblyGraph.Recipe> recipes = new ArrayList<>();
        recipes.addAll(readRecipes("ALL_RAZBORKA", "ART", "ART_R"));
        recipes.addAll(readRecipes("ALL_RAZBORKA_SLOJ", "ARTIC_ROD", "ARTIC_REB"));
        // Discover downward closure before querying cards; other components are not roots.
        var closure = FolioAssemblyGraph.build(roots, recipes, Map.of());
        List<String> skus = closure.nodes().stream().map(FolioAssemblyGraph.Node::sku).toList();
        Map<String, Set<Integer>> roles = new HashMap<>();
        Map<String, Set<Integer>> coldFlags = new HashMap<>();
        for (int offset = 0; offset < skus.size(); offset += 300) {
            var part = skus.subList(offset, Math.min(offset + 300, skus.size()));
            String sql = "SELECT COD_ARTIC, BALL4, BALL2 FROM dbo.SCL_ARTC WITH (HOLDLOCK) WHERE ID_SCLAD IN ("
                    + String.join(",", Collections.nCopies(warehouses.size(), "?")) + ") AND COD_ARTIC IN ("
                    + String.join(",", Collections.nCopies(part.size(), "?")) + ")";
            jdbc.query(c -> {
                PreparedStatement p = c.prepareStatement(sql); p.setQueryTimeout(30);
                int n = 1; for (int id : warehouses) p.setInt(n++, id); for (String sku : part) p.setString(n++, sku); return p;
            }, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                int flag = -1;
                try { var value = rs.getBigDecimal("BALL4"); if (value != null) flag = value.intValueExact(); }
                catch (ArithmeticException ignored) { /* Invalid roles require review. */ }
                roles.computeIfAbsent(FolioAssemblyGraph.key(rs.getString("COD_ARTIC")), k -> new HashSet<>()).add(flag);
                int cold = -1;
                try { var value = rs.getBigDecimal("BALL2"); if (value != null) cold = value.intValueExact(); }
                catch (ArithmeticException ignored) { /* Unknown cold sensitivity is not guessed. */ }
                coldFlags.computeIfAbsent(FolioAssemblyGraph.key(rs.getString("COD_ARTIC")), k -> new HashSet<>()).add(cold);
            });
        }
        return FolioAssemblyGraph.build(roots, recipes, roles, coldFlags);
    }

    private List<FolioAssemblyGraph.Recipe> readRecipes(String table, String parent, String child) {
        // Identifiers are internal constants only; never request input. SQL Server 2000 compatible.
        String sql = "SELECT TOP 200001 [Key], " + parent + ", " + child + ", KOL_R FROM dbo." + table + " WITH (HOLDLOCK) ORDER BY [Key]";
        var rows = jdbc.query(c -> { var p = c.prepareStatement(sql); p.setQueryTimeout(30); return p; },
                (rs, n) -> new FolioAssemblyGraph.Recipe(table, rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)));
        if (rows.size() > 200000) throw new IllegalArgumentException("Assembly recipe row limit exceeded");
        return rows;
    }
}
