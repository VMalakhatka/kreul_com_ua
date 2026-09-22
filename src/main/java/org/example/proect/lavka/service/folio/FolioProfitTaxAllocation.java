package org.example.proect.lavka.service.folio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.example.proect.lavka.dto.folio.FolioProfitTaxSettings;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.PaymentRow;
import java.util.*;
import java.util.regex.Pattern;

/** Employee counts retain the exact rational share until the document's final cent rounding. */
record FolioProfitTaxAllocation(Integer kyivCount, Integer odesaCount, Long totalCount,
        BigDecimal odesaShare, String mode, FolioProfitTaxSettings settings) {
    FolioProfitTaxAllocation(Integer kyiv,Integer odesa,Long total,BigDecimal share,String mode) {
        this(kyiv,odesa,total,share,mode,FolioProfitTaxSettings.defaults());
    }
    FolioProfitTaxAllocation withSettings(FolioProfitTaxSettings settings) {
        return new FolioProfitTaxAllocation(kyivCount,odesaCount,totalCount,odesaShare,mode,settings);
    }
    String pool(PaymentRow row) {
        String text=String.join(" ",safe(row.purposeCode()),safe(row.expenseCode()),safe(row.name()),safe(row.documentClass()));
        Set<String> found=new HashSet<>();
        for(String code:settings.retailFirmCodes()) if(matches(text,code)) found.add(code);
        for(String code:settings.wholesaleFirmCodes()) if(matches(text,code)) found.add(code);
        if(found.size()!=1) return "UNALLOCATED";
        return settings.retailFirmCodes().contains(found.iterator().next()) ? "MALAFOP" : "KONDFOP";
    }
    private static String safe(String text) {return text==null?"":text.toUpperCase(Locale.ROOT);}
    private static boolean matches(String text,String code) {
        // Historical ASCII labels remain aliases of their configured Cyrillic firm, not extra firms.
        String alias=switch(code) {case "МАЛАФОП"->"MALAFOP";case "КОНДФОП"->"KONDFOP";default->code;};
        return token(text,code)||token(text,alias);
    }
    private static boolean token(String text,String code) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_-])"+Pattern.quote(code)+"(?![\\p{L}\\p{N}_-])").matcher(text).find();
    }
    static FolioProfitTaxAllocation resolve(Integer kyiv, Integer odesa, BigDecimal legacyShare) {
        boolean counts = kyiv != null || odesa != null;
        if (counts && legacyShare != null) throw invalid("TAX_ALLOCATION_INPUT_CONFLICT",
                "Передайте либо численность обоих городов, либо старую долю odesaTaxShare, не вместе");
        if (!counts && legacyShare != null) {
            if (legacyShare.signum() < 0 || legacyShare.compareTo(BigDecimal.ONE) > 0)
                throw invalid("ODESA_TAX_SHARE_INVALID", "Доля налогов Одессы должна быть от 0 до 1");
            return new FolioProfitTaxAllocation(null, null, null, legacyShare, "LEGACY_SHARE");
        }
        if (!counts) { kyiv = 4; odesa = 3; }
        if (kyiv == null || odesa == null || kyiv < 0 || odesa < 0)
            throw invalid("EMPLOYEE_COUNTS_INVALID", "Нужны целые неотрицательные количества работников обоих городов");
        long total = (long) kyiv + odesa;
        if (total == 0) throw invalid("EMPLOYEE_COUNTS_INVALID", "Общее число работников должно быть больше нуля");
        return new FolioProfitTaxAllocation(kyiv, odesa, total,
                BigDecimal.valueOf(odesa).divide(BigDecimal.valueOf(total), 10, RoundingMode.HALF_UP), "EMPLOYEE_COUNTS");
    }
    BigDecimal kyivShare() { return BigDecimal.ONE.subtract(odesaShare); }
    BigDecimal odesaAmount(BigDecimal amount) {
        return totalCount == null ? amount.multiply(odesaShare).setScale(2, RoundingMode.HALF_UP)
                : amount.multiply(BigDecimal.valueOf(odesaCount)).divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP);
    }
    private static FolioAccountValidationException invalid(String code, String message) {
        return new FolioAccountValidationException(code, message);
    }
}
