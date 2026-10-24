package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Lossless composite identity: no guessed numeric FK and no name-only subgroup matching. */
public final class FolioProfitGroupPath {
    private static final ObjectMapper JSON=new ObjectMapper();
    private FolioProfitGroupPath() {}
    public static String id(List<String> path) {
        validate(path);
        try {return "g:"+Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(path));}
        catch(java.io.IOException e) {throw new IllegalStateException("PROFIT_SCOPE_PATH_SERIALIZATION_FAILED",e);}
    }
    public static List<String> decode(String id) {
        try {
            if(id==null||!id.startsWith("g:")||id.length()>13000) throw new IllegalArgumentException();
            var values=JSON.readValue(Base64.getUrlDecoder().decode(id.substring(2)),new TypeReference<List<String>>(){});
            validate(values);
            if(!id(values).equals(id)) throw new IllegalArgumentException();
            return List.copyOf(values);
        } catch(Exception e) {
            throw new FolioAccountValidationException("PROFIT_SCOPE_SETTINGS_INVALID","Invalid inventory group path ID; use the scope catalog");
        }
    }
    private static void validate(List<String> path) {
        if(path==null||path.isEmpty()||path.size()>6||path.stream().anyMatch(v->v==null||v.length()>255))
            throw new IllegalArgumentException("Invalid Folio group path");
    }
}
