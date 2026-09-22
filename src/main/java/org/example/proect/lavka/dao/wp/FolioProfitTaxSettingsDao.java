package org.example.proect.lavka.dao.wp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;
import java.util.Optional;

@Repository
public class FolioProfitTaxSettingsDao {
    private final JdbcTemplate jdbc;
    public FolioProfitTaxSettingsDao(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Row(long version, String retail, String wholesale) {}
    public Optional<Row> get() {
        return jdbc.query("SELECT version,retail_firm_codes,wholesale_firm_codes FROM folio_profit_tax_settings WHERE id=1",
                (rs,i)->new Row(rs.getLong(1),rs.getString(2),rs.getString(3))).stream().findFirst();
    }
    /** Single atomic statement, including concurrent first writers. Never last-writer-wins. */
    public boolean compareAndSet(long version,String retail,String wholesale) {
        if(version==0) {
            try {
                return jdbc.update("INSERT INTO folio_profit_tax_settings(id,version,retail_firm_codes,wholesale_firm_codes,updated_at) VALUES(1,1,?,?,UTC_TIMESTAMP(3))",retail,wholesale)==1;
            } catch(DuplicateKeyException conflict) { return false; }
        }
        return jdbc.update("UPDATE folio_profit_tax_settings SET version=version+1,retail_firm_codes=?,wholesale_firm_codes=?,updated_at=UTC_TIMESTAMP(3) WHERE id=1 AND version=?",
                retail,wholesale,version)==1;
    }
}
