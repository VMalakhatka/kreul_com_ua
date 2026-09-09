package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.wp.FolioProfitHistoryDao;
import org.example.proect.lavka.dao.wp.FolioProfitHistoryDao.Row;
import org.example.proect.lavka.dao.wp.FolioProfitHistoryDao.State;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.dto.folio.FolioProfitSavedReports.*;
import org.example.proect.lavka.property.DatabaseProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class FolioProfitHistoryService {
    private final FolioProfitHistoryDao dao;
    private final FolioProfitReportService calculator;
    private final ObjectMapper json;
    private final DatabaseProperties database;
    public FolioProfitHistoryService(FolioProfitHistoryDao dao,FolioProfitReportService calculator,ObjectMapper json,DatabaseProperties database) {
        this.dao=dao; this.calculator=calculator;
        this.json=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.database=database;
    }

    public Revision calculate(String month,CalculateRequest request) {
        month=month(month).toString();
        if(request==null || request.requestId()==null) throw invalid("PROFIT_REQUEST_ID_REQUIRED","Нужен requestId UUID");
        try { if(!UUID.fromString(request.requestId()).toString().equalsIgnoreCase(request.requestId())) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException e) { throw invalid("PROFIT_REQUEST_ID_INVALID","Нужен requestId UUID"); }
        String source=source();
        String raw=write(request);
        String hash=hash(month+"\n"+raw);
        String requestId=request.requestId().toLowerCase(Locale.ROOT);
        var existing=dao.byRequest(source,requestId);
        if(existing.isPresent()) return replay(existing.get(),month,hash);
        Row reserved;
        try { reserved=dao.reserve(source,month,requestId,hash,raw); }
        catch(DuplicateKeyException concurrent) { return replay(dao.byRequest(source,requestId).orElseThrow(),month,hash); }
        // Do not keep a MariaDB transaction/row lock while reading legacy Folio.
        FolioProfitReportResponse report;
        String payload;
        try {
            report=calculator.calculate(new FolioProfitReportService.Request(month,request.odesaTaxShare(),request.rubToUahRate(),
                    request.odesaMasterClassIncome(),request.odesaMasterClassReturn(),request.odesaAdditionalSalary(),
                    request.kyivStockWarehouseIds(),request.odesaStockWarehouseIds(),request.kyivAdditionalSalary()),true);
            payload=write(Objects.requireNonNull(report));
        } catch(RuntimeException failed) {
            String code=failed instanceof FolioAccountValidationException validation ? validation.getCode() : "PROFIT_SAVED_CALCULATION_FAILED";
            dao.finish(source,month,reserved.id(),"FAILED",null,false,code);
            return get(month,reserved.id());
        }
        boolean audit=auditComplete(report);
        String status=!report.ok()?"FAILED":report.complete()&&audit?"COMPLETED":"PROVISIONAL";
        // A DB/connection failure here is NOT retried and must not overwrite an unknown committed result.
        dao.finish(source,month,reserved.id(),status,payload,audit,report.ok()?null:"PROFIT_SAVED_CALCULATION_FAILED");
        return get(month,reserved.id());
    }

    private Revision replay(Row existing,String month,String hash) {
        if(!existing.month().equals(month)||!existing.requestHash().equals(hash))
            throw new FolioAccountConflictException("PROFIT_REQUEST_ID_CONFLICT","requestId уже использован для другого месяца или параметров");
        return view(existing,dao.state(existing.source(),month).orElse(null),true);
    }
    public Revision get(String month,Long revisionId) {
        month=month(month).toString(); String source=source();
        State state=dao.state(source,month).orElse(null);
        Long selected=revisionId!=null?revisionId:state==null?null:state.publishedRevisionId();
        if(selected==null) return new Revision(false,"MISSING",source,month,null,null,null,
                state==null?null:state.latestRevisionId(),state==null?null:state.latestStatus(),false,false,null,null,null,null,null);
        String key=month;
        Row row=dao.byId(source,key,selected).orElseThrow(()->new FolioAccountNotFoundException("Сохранённая ревизия этого месяца не найдена"));
        return view(row,state,true);
    }
    public History revisions(String month,int limit,Long beforeRevisionId) {
        month=month(month).toString();
        if(limit<1||limit>100||beforeRevisionId!=null&&beforeRevisionId<=0) throw invalid("PROFIT_HISTORY_PAGE_INVALID","Некорректная страница истории");
        String source=source(); State state=dao.state(source,month).orElse(null);
        var rows=dao.revisions(source,month,limit+1,beforeRevisionId==null?Long.MAX_VALUE:beforeRevisionId);
        var visible=rows.stream().limit(limit).map(row->view(row,state,false)).toList();
        return new History(true,month,visible,rows.size()>limit,rows.size()>limit?visible.get(visible.size()-1).revisionId():null);
    }
    public Range range(String from,String to) {
        YearMonth first=month(from),last=month(to);
        long length=ChronoUnit.MONTHS.between(first,last)+1;
        if(length<1||length>24) throw invalid("PROFIT_HISTORY_RANGE_INVALID","Диапазон должен содержать от1 до24месяцев");
        List<Revision> revisions=new ArrayList<>();
        for(int i=0;i<length;i++) revisions.add(get(first.plusMonths(i).toString(),null));
        List<String> missing=revisions.stream().filter(r->r.report()==null).map(Revision::month).toList();
        List<Month> months=revisions.stream().map(r->{ var p=r.report(); return new Month(r.month(),r.revisionId(),r.status(),r.latestRevisionId(),r.latestStatus(),
                p==null?null:p.calculatedAt().toString(),p==null?null:p.ruleVersion(),r.auditComplete(),p==null?null:p.inputs(),
                p==null?List.of():p.cities(),p==null?List.of():p.inventory()); }).toList();
        boolean complete=missing.isEmpty()&&revisions.stream().allMatch(r->r.status().equals("COMPLETED"));
        List<Totals> totals=List.of(FolioProfitHistoryTotals.calculate("KYIV",revisions),FolioProfitHistoryTotals.calculate("ODESA",revisions));
        return new Range(true,first.toString(),last.toString(),source(),complete&&totals.stream().allMatch(Totals::complete),missing,months,totals,
                List.of("Сохранённые ревизии: не текущие данные ФОЛИО", "Курс и налоговая доля применены отдельно для каждого месяца",
                        "NULL означает неполный диапазон/источник либо несопоставимый состав складов; остатки не суммируются",
                        "Состав диапазона фиксирован возвращёнными revisionId; публикации могут меняться между отдельными GET"));
    }
    private Revision view(Row row,State state,boolean full) {
        return new Revision(List.of("COMPLETED","PROVISIONAL").contains(row.status()),row.status(),row.source(),row.month(),row.id(),row.requestId(),
                state==null?null:state.publishedRevisionId(),state==null?null:state.latestRevisionId(),state==null?null:state.latestStatus(),
                state!=null&&Objects.equals(state.publishedRevisionId(),row.id()),row.auditComplete(),row.createdAt(),row.completedAt(),
                read(row.requestJson(),CalculateRequest.class),!full||row.reportJson()==null?null:read(row.reportJson(),FolioProfitReportResponse.class),row.errorCode());
    }
    static boolean auditComplete(FolioProfitReportResponse p) {
        return p.controls()!=null&&!p.controls().auditTruncated()&&!p.periodDiagnosticsTruncated()
                &&p.masterClass()!=null&&!p.masterClass().auditTruncated()
                &&p.sections()!=null&&p.sections().values().stream().allMatch(s->s.status().equals("AVAILABLE"));
    }
    private String source() { return sourceFromUrl(database.getUrl()); }
    static String sourceFromUrl(String url) {
        if(url!=null) {
            var matcher=java.util.regex.Pattern.compile("(?i)^jdbc:jtds:sqlserver://[^/]+/([^;?]+)").matcher(url);
            if(matcher.find()&&matcher.group(1).matches("[\\p{L}0-9_-]{1,64}")) return matcher.group(1);
        }
        throw invalid("PROFIT_SOURCE_NAMESPACE_UNAVAILABLE","Не удалось определить базу источника из конфигурации; история не смешивается между базами");
    }
    private static YearMonth month(String value) {
        try { if(value==null||!value.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
            var month=YearMonth.parse(value); if(month.getYear()<1753||month.getYear()>9998) throw new IllegalArgumentException(); return month;
        } catch(RuntimeException invalid) { throw invalid("MONTH_INVALID","Нужен месяц YYYY-MM"); }
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalStateException("Profit report JSON serialization failed"); } }
    private <T> T read(String value,Class<T> type) { try { return json.readValue(value,type); } catch(JsonProcessingException e) { throw new IllegalStateException("Saved profit report JSON is invalid"); } }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static FolioAccountValidationException invalid(String code,String text) { return new FolioAccountValidationException(code,text); }
}
