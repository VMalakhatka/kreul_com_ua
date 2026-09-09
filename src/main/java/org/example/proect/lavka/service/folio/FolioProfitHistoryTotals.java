package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.*;
import org.example.proect.lavka.dto.folio.FolioProfitSavedReports.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

final class FolioProfitHistoryTotals {
    private FolioProfitHistoryTotals() {}
    static Totals calculate(String city,List<Revision> revisions) {
        var flows=revisions.stream().map(r->r.report()==null?null:r.report().cities().stream().filter(c->c.city().equals(city)).findFirst().orElse(null)).toList();
        boolean flowScope=sameScope(revisions,r->city.equals("KYIV")?r.inputs().kyivWarehouseIds():r.inputs().odesaWarehouseIds());
        boolean stockScope=sameScope(revisions,r->city.equals("KYIV")?r.inputs().kyivStockWarehouseIds():r.inputs().odesaStockWarehouseIds());
        InventoryResult opening=inventory(revisions.get(0),city),closing=inventory(revisions.get(revisions.size()-1),city);
        BigDecimal first=stockScope&&opening!=null?opening.openingAccountingValue():null;
        BigDecimal last=stockScope&&closing!=null?closing.closingAccountingValue():null;
        return new Totals(city,flowScope?sum(flows,CityResult::baseGrossProfit):null,
                sum(flows,CityResult::manualGrossAdjustments),flowScope?sum(flows,CityResult::grossProfit):null,
                sum(flows,CityResult::operatingExpenses),flowScope?sum(flows,CityResult::profit):null,
                first,last,first==null||last==null?null:last.subtract(first),
                flowScope&&stockScope&&revisions.stream().allMatch(r->r.status().equals("COMPLETED")));
    }
    private static InventoryResult inventory(Revision revision,String city) { return revision.report()==null?null:
            revision.report().inventory().stream().filter(i->i.city().equals(city)).findFirst().orElse(null); }
    private static BigDecimal sum(List<CityResult> rows,Function<CityResult,BigDecimal> field) {
        BigDecimal total=BigDecimal.ZERO;
        for(var row:rows) { if(row==null||field.apply(row)==null) return null; total=total.add(field.apply(row)); }
        return total;
    }
    private static boolean sameScope(List<Revision> rows,Function<org.example.proect.lavka.dto.folio.FolioProfitReportResponse,List<Integer>> scope) {
        Set<Integer> baseline=null;
        for(var row:rows) {
            if(row.report()==null||row.report().inputs()==null) return false;
            List<Integer> ids=scope.apply(row.report()); if(ids==null||ids.isEmpty()) return false;
            var current=Set.copyOf(ids); if(baseline!=null&&!baseline.equals(current)) return false; baseline=current;
        }
        return true;
    }
}
