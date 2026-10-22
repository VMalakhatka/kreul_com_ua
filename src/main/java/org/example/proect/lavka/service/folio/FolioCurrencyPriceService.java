package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioCurrencyPriceDao;
import org.example.proect.lavka.dao.folio.FolioCurrencyPriceDao.Input;
import org.example.proect.lavka.dao.folio.FolioCurrencyPriceDao.Row;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FolioCurrencyPriceService {
    public record Result(String token,String status,String sourceDatabase,List<Input> input,List<Row> before,List<Row> after,
                         List<String> missingCards,List<String> missingRetail,int cardRows,int retailRows,String expiresAt,String error) {}
    private static class Job {
        final String token=UUID.randomUUID().toString(); final Instant created=Instant.now();
        final List<Input> input;final List<Row> before;volatile String status="PREVIEW";volatile String error;
        volatile List<Row> after=List.of();
        Job(List<Input> input,List<Row> before){this.input=input;this.before=before;}
    }
    private final FolioCurrencyPriceDao dao;private final FolioAccountingPriceDao mutex;
    private final TransactionTemplate tx;private final Map<String,Job> jobs=new ConcurrentHashMap<>();
    public FolioCurrencyPriceService(FolioCurrencyPriceDao dao,FolioAccountingPriceDao mutex,
        @Qualifier("mssqlTransactionManager") PlatformTransactionManager manager) {
        this.dao=dao;this.mutex=mutex;tx=new TransactionTemplate(manager);tx.setTimeout(180);
    }
    public static List<Input> validate(List<Input> input) {
        if(input==null || input.isEmpty() || input.size()>500)throw new IllegalArgumentException("INVALID_INPUT");
        var seen=new HashSet<String>();var output=new ArrayList<Input>();
        for(Input r:input) {
            if(r==null || r.sku()==null || r.price()==null || r.currency()==null)throw new IllegalArgumentException("INVALID_INPUT");
            String sku=r.sku().trim(), price=r.price().trim().replace(',','.'),currency=r.currency().trim();
            if(sku.isEmpty()||sku.length()>20||currency.isEmpty()||currency.length()>4||price.length()>64
                || !price.matches("[+-]?\\d+(\\.\\d+)?") || sku.chars().anyMatch(Character::isISOControl)
                || currency.chars().anyMatch(Character::isISOControl) || !Charset.forName("windows-1251").newEncoder().canEncode(sku+currency)
                || !seen.add(sku.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("INVALID_INPUT");
            BigDecimal value=new BigDecimal(price);
            if(!Double.isFinite(value.doubleValue()) || (value.signum()!=0 && value.doubleValue()==0))throw new IllegalArgumentException("INVALID_INPUT");
            output.add(new Input(sku,value.stripTrailingZeros().toPlainString(),currency));
        }
        return List.copyOf(output);
    }
    public synchronized Result preview(List<Input> input) {
        jobs.values().removeIf(j->!j.status.equals("APPLYING") && j.created.plusSeconds(86400).isBefore(Instant.now()));
        if(jobs.size()>=100)throw new IllegalStateException("PREVIEW_CAPACITY");
        var valid=validate(input);dao.preflight();var job=new Job(valid,dao.snapshot(valid,false));jobs.put(job.token,job);return result(job);
    }
    private Job job(String token) {Job j=token==null?null:jobs.get(token);if(j==null)throw new IllegalArgumentException("UNKNOWN_TOKEN");return j;}
    public Result status(String token){Job j=job(token);synchronized(j){if(j.status.equals("PREVIEW")&&j.created.plusSeconds(900).isBefore(Instant.now()))j.status="EXPIRED";}return result(j);}
    public Result apply(String token,boolean confirmed) {
        if(!confirmed)throw new IllegalArgumentException("CONFIRM_REQUIRED");Job j=job(token);
        synchronized(j) {
            if(!j.status.equals("PREVIEW"))return result(j);
            if(j.created.plusSeconds(900).isBefore(Instant.now())){j.status="EXPIRED";return result(j);}
            if(j.before.isEmpty()){j.status="EMPTY";return result(j);}j.status="APPLYING";
        }
        try {
            var after=tx.execute(ignored->{
                // Shared with accounting recalculations; all DB work stays on one managed connection.
                mutex.acquireRecalculationMutex(5000);dao.preflight();
                if(!j.before.equals(dao.snapshot(j.input,true)))throw new StalePreview();
                // Preserve Access order: every card update, then every warehouse-7 retail update.
                for(int i=0;i<j.input.size();i++) {
                    final int n=i;long expected=j.before.stream().filter(r->r.input()==n&&r.table().equals("SCL_ARTC")).count();
                    if(dao.updateCards(j.input.get(i))!=expected)throw new IllegalStateException("ROW_COUNT_CHANGED");
                }
                for(int i=0;i<j.input.size();i++) {
                    final int n=i;long expected=j.before.stream().filter(r->r.input()==n&&r.table().equals("SCL_PRIC")).count();
                    if(dao.updateRetail(j.input.get(i))!=expected)throw new IllegalStateException("ROW_COUNT_CHANGED");
                }
                var saved=dao.snapshot(j.input,true);
                if(saved.size()!=j.before.size())throw new IllegalStateException("VERIFY_FAILED");
                for(Row r:saved) {
                    Input target=j.input.get(r.input());
                    if(r.price()==null || Double.compare(new BigDecimal(r.price()).doubleValue(),new BigDecimal(target.price()).doubleValue())!=0
                        || (r.table().equals("SCL_ARTC")&&!target.currency().equals(r.currency())))throw new IllegalStateException("VERIFY_FAILED");
                }
                return saved;
            });
            j.after=after;j.status="APPLIED";
        } catch(StalePreview e){j.error="PREVIEW_CHANGED";j.status="STALE";}
          catch(RuntimeException e){j.error="CHECK_OUTCOME";j.status="UNKNOWN";}
        return result(j);
    }
    private static class StalePreview extends RuntimeException {}
    private Result result(Job j) {
        var missingCards=new ArrayList<String>();var missingRetail=new ArrayList<String>();int cards=0,retail=0;
        for(int i=0;i<j.input.size();i++) {
            final int n=i;int c=(int)j.before.stream().filter(r->r.input()==n&&r.table().equals("SCL_ARTC")).count();
            int p=(int)j.before.stream().filter(r->r.input()==n&&r.table().equals("SCL_PRIC")).count();
            cards+=c;retail+=p;if(c==0)missingCards.add(j.input.get(i).sku());if(p==0)missingRetail.add(j.input.get(i).sku());
        }
        return new Result(j.token,j.status,"Paint_Ua",j.input,j.before,j.after,missingCards,missingRetail,cards,retail,j.created.plusSeconds(900).toString(),j.error);
    }
}
