package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Read-only packaging from the same source warehouse as card_tov_export. */
@Repository
public class FolioProductPackagingDao {
    private final NamedParameterJdbcTemplate jdbc;
    public FolioProductPackagingDao(@Qualifier("folioNamedJdbc") NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Item(String sku, BigDecimal unitsPerPack) {}
    public List<Item> read(List<String> skus) {
        return jdbc.query("""
            SELECT COD_ARTIC, EDN_V_UPAK FROM dbo.SCL_ARTC
             WHERE ID_SCLAD=7 AND COD_ARTIC IN (:skus)
            """, Map.of("skus",skus), (rs,i)->new Item(rs.getString("COD_ARTIC").trim(),rs.getBigDecimal("EDN_V_UPAK")));
    }
}
