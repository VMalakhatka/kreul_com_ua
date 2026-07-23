package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.dto.folio.FolioPartnerItemResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class FolioPartnerDao {

    private static final String ORDER_BY = " ORDER BY ISNULL(NAMEP_USER, ''), ISNULL(NAME_USER, ''), N_USER";

    private final JdbcTemplate jdbc;

    public FolioPartnerDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(String q, List<String> types) {
        QueryParts parts = buildWhere(q, types);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dbo._PARTNER p" + parts.whereSql(),
                Long.class,
                parts.params().toArray()
        );
        return total == null ? 0 : total;
    }

    public List<FolioPartnerItemResponse> find(String q, List<String> types, int limit, int offset) {
        QueryParts parts = buildWhere(q, types);
        List<Object> params = new ArrayList<>(parts.params());

        StringBuilder sql = new StringBuilder();
        sql.append("""
                SELECT TOP\040""").append(limit).append("""
                       N_USER, NAME_USER, NAMEP_USER, MY_ORGANIZ,
                       BANK_USER, SCT_B_USER, COD_B_USER, TOWNB_USER
                FROM dbo._PARTNER p
                """);
        sql.append(parts.whereSql());

        if (offset > 0) {
            sql.append(parts.whereSql().isBlank() ? " WHERE " : " AND ");
            sql.append("""
                    p.N_USER NOT IN (
                        SELECT TOP\040""").append(offset).append("""
                               p2.N_USER
                        FROM dbo._PARTNER p2
                    """);
            QueryParts subParts = buildWhere("p2", q, types);
            sql.append(subParts.whereSql());
            sql.append(ORDER_BY.replace("NAMEP_USER", "p2.NAMEP_USER")
                    .replace("NAME_USER", "p2.NAME_USER")
                    .replace("N_USER", "p2.N_USER"));
            sql.append(")");
            params.addAll(subParts.params());
        }

        sql.append(ORDER_BY);

        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            String id = trimToNull(rs.getString("N_USER"));
            String shortName = trimToNull(rs.getString("NAMEP_USER"));
            String name = trimToNull(rs.getString("NAME_USER"));
            String type = trimToNull(rs.getString("MY_ORGANIZ"));
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("nUser", id);

            return new FolioPartnerItemResponse(
                    id,
                    shortName,
                    name,
                    type,
                    typeLabel(type),
                    emptyIfNull(trimToNull(rs.getString("BANK_USER"))),
                    emptyIfNull(trimToNull(rs.getString("SCT_B_USER"))),
                    emptyIfNull(trimToNull(rs.getString("COD_B_USER"))),
                    emptyIfNull(trimToNull(rs.getString("TOWNB_USER"))),
                    // TODO: Fill phone/city from _PARTNER_PL after the relationship is confirmed.
                    "",
                    "",
                    raw
            );
        }, params.toArray());
    }

    private static QueryParts buildWhere(String q, List<String> types) {
        return buildWhere("p", q, types);
    }

    private static QueryParts buildWhere(String alias, String q, List<String> types) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (types != null && !types.isEmpty()) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < types.size(); i++) {
                if (i > 0) {
                    in.append(", ");
                }
                in.append("?");
                params.add(types.get(i));
            }
            clauses.add(alias + ".MY_ORGANIZ IN (" + in + ")");
        }

        String search = trimToNull(q);
        if (search != null) {
            clauses.add("(" + alias + ".NAME_USER LIKE ? OR "
                    + alias + ".NAMEP_USER LIKE ? OR CAST(" + alias + ".N_USER AS varchar(50)) LIKE ?)");
            String pattern = "%" + search + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }

        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        return new QueryParts(where, params);
    }

    private static String typeLabel(String type) {
        if ("Я".equals(type)) {
            return "Моя организация";
        }
        if ("П".equals(type)) {
            return "Партнер";
        }
        if ("Д".equals(type)) {
            return "Дилер";
        }
        if ("К".equals(type)) {
            return "Покупатель";
        }
        if ("Т".equals(type)) {
            return "Поставщик";
        }
        if ("I".equals(type)) {
            return "Иностранный поставщик";
        }
        return "";
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record QueryParts(String whereSql, List<Object> params) {
    }
}
