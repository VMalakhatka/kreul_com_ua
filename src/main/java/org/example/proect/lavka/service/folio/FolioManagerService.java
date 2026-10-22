package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.*;
import org.example.proect.lavka.dao.folio.FolioManagerDao.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FolioManagerService {
    public record Result(String token,String status,String sourceDatabase,String operation,List<Map<String,String>> input,
                         List<Target> before,List<Target> after,Map<String,Table> outputs,List<String> missing,List<String> issues,String expiresAt) {}
    private static class Job {
        final String token=UUID.randomUUID().toString();final Instant created=Instant.now();
        final FolioManagerOperation op;final List<Map<String,String>> input;final List<Target> before;
        volatile List<Target> after=List.of();volatile String status;final Map<String,Table> outputs;
        final List<String> issues;
        Job(FolioManagerOperation op,List<Map<String,String>> input,List<Target> before,Map<String,Table> outputs,List<String> issues) {
            this.op=op;this.input=input;this.before=before;this.outputs=outputs;this.issues=issues;
            status=!issues.isEmpty()?"BLOCKED":op.writes()?"PREVIEW":"READY";
        }
    }
    private final FolioManagerDao dao;private final FolioAccountingPriceDao mutex;private final FolioAssemblyCalculator assembly;
    private final TransactionTemplate tx;private final Map<String,Job> jobs=new ConcurrentHashMap<>();
    public FolioManagerService(FolioManagerDao dao,FolioAccountingPriceDao mutex,FolioAssemblyCalculator assembly,
        @Qualifier("mssqlTransactionManager") PlatformTransactionManager manager) {
        this.dao=dao;this.mutex=mutex;this.assembly=assembly;tx=new TransactionTemplate(manager);tx.setTimeout(180);
    }
    public synchronized Result preview(String operation,List<Map<String,String>> input) {
        var op=FolioManagerOperation.valueOf(operation==null?"":operation);var valid=op.validate(input);
        jobs.values().removeIf(j->!j.status.equals("APPLYING")&&j.created.plusSeconds(86400).isBefore(Instant.now()));
        if(jobs.size()>=100)throw new IllegalStateException("CAPACITY");dao.preflight(op);
        List<Target> before=op.writes()?dao.snapshot(op,valid,false):List.of();
        var outputs=new LinkedHashMap<String,Table>();var issues=new ArrayList<String>();
        if(op==FolioManagerOperation.BASE)outputs.put("BASE",dao.base(valid));
        if(op==FolioManagerOperation.ASSEMBLY)outputs.putAll(assembly.calculate(valid));
        var job=new Job(op,valid,before,outputs,List.copyOf(issues));jobs.put(job.token,job);return result(job);
    }
    private Job job(String token){Job j=token==null?null:jobs.get(token);if(j==null)throw new IllegalArgumentException("UNKNOWN_TOKEN");return j;}
    public Result status(String token){Job j=job(token);synchronized(j){expire(j);return result(j);}}
    private void expire(Job j){if(j.status.equals("PREVIEW")&&j.created.plusSeconds(900).isBefore(Instant.now()))j.status="EXPIRED";}
    public Result apply(String token,boolean confirmed) {
        if(!confirmed)throw new IllegalArgumentException("CONFIRM_REQUIRED");Job j=job(token);
        synchronized(j){expire(j);if(!j.status.equals("PREVIEW"))return result(j);
            if(j.before.isEmpty()&&j.op!=FolioManagerOperation.DESCRIPTION){j.status="EMPTY";return result(j);}j.status="APPLYING";}
        try {
            var after=tx.execute(ignored->{
                mutex.acquireRecalculationMutex(5000);dao.preflight(j.op);
                if(!j.before.equals(dao.snapshot(j.op,j.input,true)))throw new StalePreview();
                // Owner confirmed: append missing keys, then update all descriptions.
                if(j.op==FolioManagerOperation.DESCRIPTION)for(int i=0;i<j.input.size();i++) {
                    final int n=i;
                    if(j.before.stream().noneMatch(r->r.input()==n)&&dao.insertDescription(j.input.get(i))!=1)throw new IllegalStateException("INSERT_COUNT");
                }
                for(int i=0;i<j.input.size();i++) {
                    final int index=i;long expected=j.op==FolioManagerOperation.DESCRIPTION?1:j.before.stream().filter(r->r.input()==index).count();
                    if(dao.update(j.op,j.input.get(i))!=expected)throw new IllegalStateException("UPDATE_COUNT");
                }
                var saved=dao.snapshot(j.op,j.input,true);
                if(saved.size()!=(j.op==FolioManagerOperation.DESCRIPTION?j.input.size():j.before.size()))throw new IllegalStateException("VERIFY_COUNT");
                for(var r:saved)for(var f:j.op.fields)if(!Objects.equals(r.values().get(f),j.input.get(r.input()).get(f)))throw new IllegalStateException("VERIFY_VALUE");
                return saved;
            });
            synchronized(j){j.after=after;j.status="APPLIED";}
        }catch(StalePreview e){j.status="STALE";}catch(RuntimeException e){j.status="UNKNOWN";}
        return result(j);
    }
    private static class StalePreview extends RuntimeException {}
    private Result result(Job j) {
        var missing=new ArrayList<String>();
        if(j.op.writes())for(int i=0;i<j.input.size();i++){final int n=i;if(j.before.stream().noneMatch(r->r.input()==n))missing.add(j.input.get(i).get(j.op.key));}
        return new Result(j.token,j.status,"Paint_Ua",j.op.name(),j.input,j.before,j.after,j.outputs,missing,j.issues,j.created.plusSeconds(900).toString());
    }
}
