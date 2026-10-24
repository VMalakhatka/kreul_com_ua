package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioProfitReadBudget;
import org.example.proect.lavka.dao.folio.FolioProfitScopeCatalogDao;
import org.example.proect.lavka.dao.wp.FolioProfitScopeSettingsDao;
import org.example.proect.lavka.dto.folio.FolioProfitScopeCatalog;
import org.example.proect.lavka.dto.folio.FolioProfitScopeCatalog.*;
import org.example.proect.lavka.dto.folio.FolioProfitScopeSettings;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Stream;

@Service
public class FolioProfitScopeSettingsService {
    private final FolioProfitScopeSettingsDao dao;
    private final FolioProfitScopeCatalogDao source;
    private final ObjectMapper json;
    private final FolioProfitReportProperties properties;
    public record Captured(FolioProfitScopeSettings settings,FolioProfitScopeCatalog catalog) {}
    public FolioProfitScopeSettingsService(FolioProfitScopeSettingsDao dao,FolioProfitScopeCatalogDao source,
            ObjectMapper json,FolioProfitReportProperties properties) {
        this.dao=dao;this.source=source;this.json=json;this.properties=properties;
    }
    public FolioProfitScopeSettings get() {
        return dao.get().map(row->{
            try {
                var value=validate(json.readValue(row.settingsJson(),FolioProfitScopeSettings.class));
                if(value.version()!=row.version()) throw new IllegalArgumentException("Scope version mismatch");
                return value;
            } catch(Exception e) {throw new IllegalStateException("PROFIT_SCOPE_SETTINGS_STORAGE_INVALID",e);}
        }).orElseGet(()->defaults(properties));
    }
    public static FolioProfitScopeSettings defaults(FolioProfitReportProperties p) {
        return validate(new FolioProfitScopeSettings(0L,p.getKyivWarehouseIds(),List.of(p.getOdesaWarehouseId()),
                p.getKyivStockWarehouseIds(),List.of(p.getOdesaWarehouseId()),List.of(),List.of()));
    }
    public FolioProfitScopeSettings put(FolioProfitScopeSettings request) {
        var value=validate(request);
        // The next value must remain readable by the strict version contract.
        if(value.version()==Long.MAX_VALUE-1) throw invalid();
        validateCatalog(value,catalog());
        var next=value.withVersion(value.version()+1);
        String payload;
        try {payload=json.writeValueAsString(next);}
        catch(Exception e) {throw new IllegalStateException("PROFIT_SCOPE_SETTINGS_SERIALIZATION_FAILED",e);}
        if(!dao.compareAndSet(value.version(),payload)) throw conflict();
        return next;
    }
    public Captured capture(Long expectedVersion) {
        if(expectedVersion!=null&&(expectedVersion<0||expectedVersion==Long.MAX_VALUE)) throw invalid();
        var value=get();
        if(expectedVersion!=null&&!expectedVersion.equals(value.version())) throw conflict();
        var catalog=catalog();
        validateCatalog(value,catalog);
        return new Captured(value,catalog);
    }
    public FolioProfitScopeCatalog catalog() {
        try(var budget=new FolioProfitReadBudget(60,30)) {
            var warehouses=List.copyOf(source.warehouses());
            Map<String,Group> groups=new LinkedHashMap<>();
            Map<String,Subgroup> subgroups=new LinkedHashMap<>();
            for(var path:source.groupPaths()) {
                for(int depth=1;depth<=path.size();depth++) {
                    var prefix=List.copyOf(path.subList(0,depth));
                    String id=FolioProfitGroupPath.id(prefix),name=prefix.get(depth-1);
                    if(depth==1) groups.putIfAbsent(id,new Group(id,name,prefix));
                    else subgroups.putIfAbsent(id,new Subgroup(id,FolioProfitGroupPath.id(prefix.subList(0,1)),
                            FolioProfitGroupPath.id(prefix.subList(0,depth-1)),name,depth,prefix));
                }
            }
            return new FolioProfitScopeCatalog(warehouses,List.copyOf(groups.values()),List.copyOf(subgroups.values()));
        }
    }
    public static FolioProfitScopeSettings effective(Captured captured,List<Integer> kyivOverride,List<Integer> odesaOverride) {
        var baseline=captured.settings();
        var value=validate(baseline.withStockWarehouses(kyivOverride==null?baseline.kyivStockWarehouseIds():kyivOverride,
                odesaOverride==null?baseline.odesaStockWarehouseIds():odesaOverride));
        if(captured.catalog()!=null) validateCatalog(value,captured.catalog());
        return value;
    }
    /** A self-contained display snapshot: never consult today's catalog when reading a saved revision. */
    public static FolioProfitScopeCatalog selectedCatalog(FolioProfitScopeSettings value,FolioProfitScopeCatalog catalog) {
        if(catalog==null) return null;
        var warehouses=new HashSet<Integer>();
        Stream.of(value.kyivWarehouseIds(),value.odesaWarehouseIds(),value.kyivStockWarehouseIds(),value.odesaStockWarehouseIds())
                .forEach(warehouses::addAll);
        var paths=new HashSet<String>();
        for(var path:excludedPaths(value)) for(int depth=1;depth<=path.size();depth++)
            paths.add(FolioProfitGroupPath.id(path.subList(0,depth)));
        return new FolioProfitScopeCatalog(catalog.warehouses().stream().filter(w->warehouses.contains(w.id())).toList(),
                catalog.groups().stream().filter(g->paths.contains(g.id())).toList(),
                catalog.subgroups().stream().filter(g->paths.contains(g.id())).toList());
    }
    public static List<List<String>> excludedPaths(FolioProfitScopeSettings value) {
        return Stream.concat(value.excludedInventoryGroupIds().stream(),value.excludedInventorySubgroupIds().stream())
                .map(FolioProfitGroupPath::decode).toList();
    }
    public static FolioProfitScopeSettings validate(FolioProfitScopeSettings value) {
        if(value==null||value.version()==null||value.version()<0||value.version()==Long.MAX_VALUE) throw invalid();
        for(var ids:Arrays.asList(value.kyivWarehouseIds(),value.odesaWarehouseIds(),value.kyivStockWarehouseIds(),value.odesaStockWarehouseIds())) {
            if(ids==null||ids.isEmpty()||ids.size()>50||ids.stream().anyMatch(id->id==null||id<=0)||new HashSet<>(ids).size()!=ids.size()) throw invalid();
        }
        if(!Collections.disjoint(value.kyivWarehouseIds(),value.odesaWarehouseIds())
                ||!Collections.disjoint(value.kyivStockWarehouseIds(),value.odesaStockWarehouseIds()))
            throw new FolioAccountValidationException("PROFIT_SCOPE_WAREHOUSE_OVERLAP","A warehouse cannot belong to both cities within sales or within inventory");
        var groups=value.excludedInventoryGroupIds(); var subgroups=value.excludedInventorySubgroupIds();
        if(groups==null||subgroups==null||groups.size()+subgroups.size()>100) throw invalid();
        if(new HashSet<>(groups).size()!=groups.size()||new HashSet<>(subgroups).size()!=subgroups.size()) throw invalid();
        for(String id:groups) if(FolioProfitGroupPath.decode(id).size()!=1) throw groupNotFound();
        for(String id:subgroups) if(FolioProfitGroupPath.decode(id).size()<2) throw groupNotFound();
        return value;
    }
    public static void validateCatalog(FolioProfitScopeSettings value,FolioProfitScopeCatalog catalog) {
        var warehouseIds=new HashSet<Integer>(); catalog.warehouses().forEach(w->warehouseIds.add(w.id()));
        if(Stream.of(value.kyivWarehouseIds(),value.odesaWarehouseIds(),value.kyivStockWarehouseIds(),value.odesaStockWarehouseIds())
                .flatMap(List::stream).anyMatch(id->!warehouseIds.contains(id)))
            throw new FolioAccountValidationException("PROFIT_SCOPE_WAREHOUSE_NOT_FOUND","A selected warehouse is absent from the Folio catalog");
        var groups=new HashSet<String>();catalog.groups().forEach(g->groups.add(g.id()));
        var subgroups=new HashSet<String>();catalog.subgroups().forEach(g->subgroups.add(g.id()));
        if(!groups.containsAll(value.excludedInventoryGroupIds())||!subgroups.containsAll(value.excludedInventorySubgroupIds())) throw groupNotFound();
    }
    private static FolioAccountValidationException invalid() {
        return new FolioAccountValidationException("PROFIT_SCOPE_SETTINGS_INVALID","Supply a valid version, four unique non-empty warehouse lists (up to 50 each), and two exclusion lists (up to 100 branches total)");
    }
    private static FolioAccountValidationException groupNotFound() {
        return new FolioAccountValidationException("PROFIT_SCOPE_GROUP_NOT_FOUND","A selected inventory group path is absent or belongs to a different level; reload the scope catalog");
    }
    private static FolioAccountConflictException conflict() {
        return new FolioAccountConflictException("PROFIT_SCOPE_SETTINGS_VERSION_CONFLICT","Scope settings changed; reload before saving or starting another calculation");
    }
}
