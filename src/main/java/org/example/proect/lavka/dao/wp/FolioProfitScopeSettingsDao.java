package org.example.proect.lavka.dao.wp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class FolioProfitScopeSettingsDao {
    private final JdbcTemplate jdbc;
    public FolioProfitScopeSettingsDao(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Row(long version,String settingsJson) {}
    public Optional<Row> get() {
        return jdbc.query("SELECT version,settings_json FROM folio_profit_scope_settings WHERE id=1",
                (rs,i)->new Row(rs.getLong(1),rs.getString(2))).stream().findFirst();
    }
    public boolean compareAndSet(long version,String json) {
        if(version==0) {
            try {return jdbc.update("INSERT INTO folio_profit_scope_settings(id,version,settings_json,updated_at) VALUES(1,1,?,UTC_TIMESTAMP(3))",json)==1;}
            catch(DuplicateKeyException conflict) {return false;}
        }
        return jdbc.update("UPDATE folio_profit_scope_settings SET version=version+1,settings_json=?,updated_at=UTC_TIMESTAMP(3) WHERE id=1 AND version=?",json,version)==1;
    }
}
