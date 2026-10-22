package org.example.proect.lavka.service.folio;

import java.nio.charset.Charset;
import java.util.*;

/** Closed list, approved from Макросы.xls. No arbitrary SQL/table/column from clients. */
public enum FolioManagerOperation {
    NAME("SCL_ARTC", "COD_ARTIC", List.of("NAME_ARTIC"), List.of(200)),
    GROUPS("SCL_ARTC", "CODE_ARTIC", List.of("NGROUP_TVR","NGROUP_TV2","NGROUP_TV3","NGROUP_TV4","NGROUP_TV5","NGROUP_TV6"), List.of(30,30,30,30,30,30)),
    UNIT("SCL_ARTC", "COD_ARTIC", List.of("EDIN_IZMER"), List.of(10)),
    DESCRIPTION("ALL_ARTC", "COD_ARTIC", List.of("DESCRIPTION"), List.of(5000)),
    BASE("ALL_CENA", "ARTIC_BAZA", List.of(), List.of()),
    ASSEMBLY("", "ARTIC_roditel", List.of("ARTIC_rebenok","KOL_rod_razob","kol_reb_pol","CENA_REB","SLOG"), List.of(20,64,64,64,1));

    public final String table, key;
    public final List<String> fields;
    public final List<Integer> lengths;
    FolioManagerOperation(String table,String key,List<String> fields,List<Integer> lengths) {
        this.table=table;this.key=key;this.fields=fields;this.lengths=lengths;
    }
    public boolean writes(){return this!=BASE && this!=ASSEMBLY;}
    public List<String> columns(){var c=new ArrayList<String>();c.add(key);c.addAll(fields);return List.copyOf(c);}
    public List<Map<String,String>> validate(List<Map<String,String>> rows) {
        if(rows==null||rows.isEmpty()||rows.size()>500)throw new IllegalArgumentException("INVALID_ROWS");
        var result=new ArrayList<Map<String,String>>();var seen=new HashSet<String>();
        for(var row:rows) {
            if(row==null||!row.keySet().equals(new HashSet<>(columns())))throw new IllegalArgumentException("INVALID_COLUMNS");
            var normalized=new LinkedHashMap<String,String>();
            for(String c:columns()) {
                String v=row.get(c);if(v!=null){v=v.trim();if(v.isEmpty())v=null;}
                int limit=c.equals(key)?20:lengths.get(fields.indexOf(c));
                if(v!=null&&(v.length()>limit||!Charset.forName("windows-1251").newEncoder().canEncode(v)
                    ||v.chars().anyMatch(n->Character.isISOControl(n)&&!(this==DESCRIPTION&&c.equals("DESCRIPTION")&&(n==10||n==13||n==9)))))throw new IllegalArgumentException("INVALID_VALUE");
                normalized.put(c,v);
            }
            String sku=normalized.get(key);
            if(sku==null&&this!=ASSEMBLY)throw new IllegalArgumentException("MISSING_ARTICLE");
            if(writes()&&!seen.add(sku.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("DUPLICATE_ARTICLE");
            if(this==ASSEMBLY) {
                for(String f:List.of("KOL_rod_razob","kol_reb_pol","CENA_REB")) {
                    String v=normalized.get(f);
                    if(v!=null){v=v.replace(',','.');if(!v.matches("[+-]?\\d+(\\.\\d+)?")||!Double.isFinite(Double.parseDouble(v)))throw new IllegalArgumentException("INVALID_NUMBER");if(f.equals("kol_reb_pol")){
                        var rounded=new java.math.BigDecimal(v).setScale(0,java.math.RoundingMode.HALF_EVEN);
                        if(rounded.compareTo(java.math.BigDecimal.valueOf(-32768))<0||rounded.compareTo(java.math.BigDecimal.valueOf(32767))>0)throw new IllegalArgumentException("INVALID_NUMBER");
                        v=rounded.toPlainString();
                    }
                    if(f.equals("CENA_REB")&&!Float.isFinite(Float.parseFloat(v)))throw new IllegalArgumentException("INVALID_NUMBER");
                    normalized.put(f,v);}
                }
                if(!Set.of("0","1").contains(Objects.toString(normalized.get("SLOG"),"")))throw new IllegalArgumentException("SLOG_REQUIRED");
            }
            result.add(Collections.unmodifiableMap(normalized));
        }
        return List.copyOf(result);
    }
}
