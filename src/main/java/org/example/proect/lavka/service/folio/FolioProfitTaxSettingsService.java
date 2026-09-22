package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.wp.FolioProfitTaxSettingsDao;
import org.example.proect.lavka.dto.folio.FolioProfitTaxSettings;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class FolioProfitTaxSettingsService {
    private final FolioProfitTaxSettingsDao dao;
    private final ObjectMapper json;
    public FolioProfitTaxSettingsService(FolioProfitTaxSettingsDao dao,ObjectMapper json) {this.dao=dao;this.json=json;}
    public FolioProfitTaxSettings get() {
        return dao.get().map(row->{
            try {
                return validate(new FolioProfitTaxSettings(row.version(),
                        json.readValue(row.retail(),new TypeReference<List<String>>(){}),
                        json.readValue(row.wholesale(),new TypeReference<List<String>>(){})));
            } catch(Exception e) { throw new IllegalStateException("PROFIT_TAX_SETTINGS_STORAGE_INVALID",e); }
        }).orElseGet(FolioProfitTaxSettings::defaults);
    }
    public FolioProfitTaxSettings put(FolioProfitTaxSettings request) {
        var value=validate(request);
        String retail,wholesale;
        try {retail=json.writeValueAsString(value.retailFirmCodes());wholesale=json.writeValueAsString(value.wholesaleFirmCodes());}
        catch(Exception e) {throw new IllegalStateException("PROFIT_TAX_SETTINGS_SERIALIZATION_FAILED",e);}
        if(!dao.compareAndSet(value.version(),retail,wholesale))
            throw new FolioAccountConflictException("PROFIT_TAX_SETTINGS_VERSION_CONFLICT","Tax settings changed; reload before saving again");
        return new FolioProfitTaxSettings(value.version()+1,value.retailFirmCodes(),value.wholesaleFirmCodes());
    }
    static FolioProfitTaxSettings validate(FolioProfitTaxSettings value) {
        if(value==null || value.version()==null || value.version()<0 || value.version()==Long.MAX_VALUE) throw invalid();
        var retail=codes(value.retailFirmCodes());var wholesale=codes(value.wholesaleFirmCodes());
        if(retail.stream().anyMatch(wholesale::contains)) throw new FolioAccountValidationException("PROFIT_TAX_FIRM_OVERLAP","A tax firm cannot belong to both retail and wholesale");
        return new FolioProfitTaxSettings(value.version(),retail,wholesale);
    }
    private static List<String> codes(List<String> input) {
        if(input==null || input.size()>100) throw invalid();
        List<String> result=new ArrayList<>();
        for(String raw:input) {
            if(raw==null) throw invalid();
            String code=raw.trim().toUpperCase(Locale.ROOT);
            code=switch(code) {case "MALAFOP"->"МАЛАФОП";case "KONDFOP"->"КОНДФОП";default->code;};
            if(!code.matches("[\\p{L}\\p{N}_-]{1,64}") || result.contains(code)) throw invalid();
            result.add(code);
        }
        return List.copyOf(result);
    }
    private static FolioAccountValidationException invalid() {return new FolioAccountValidationException("PROFIT_TAX_SETTINGS_INVALID","Version and two unique lists of firm codes are required (up to 100 codes, 1–64 letters/digits/_/- each)");}
}
