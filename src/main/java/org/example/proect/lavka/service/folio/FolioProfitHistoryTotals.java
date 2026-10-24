package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.*;
import org.example.proect.lavka.dto.folio.FolioProfitSavedReports.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

final class FolioProfitHistoryTotals {
    private FolioProfitHistoryTotals() {}
    static Totals calculate(String city,List<Revision> revisions) {
        // Aggregate the saved values, not a recalculation with a common warehouse scope.
        var flows=revisions.stream().map(r->city(r,city)).toList();
        InventoryResult opening=inventory(revisions.get(0),city),closing=inventory(revisions.get(revisions.size()-1),city);
        BigDecimal first=opening!=null?opening.openingAccountingValue():null;
        BigDecimal last=closing!=null?closing.closingAccountingValue():null;
        BigDecimal base=sum(flows,CityResult::baseGrossProfit);
        BigDecimal manual=sum(flows,CityResult::manualGrossAdjustments);
        BigDecimal gross=sum(flows,CityResult::grossProfit);
        BigDecimal expenses=sum(flows,CityResult::operatingExpenses);
        BigDecimal profit=sum(flows,CityResult::profit);
        boolean complete=revisions.stream().allMatch(r->"COMPLETED".equals(r.status()))
                &&Stream.of(base,manual,gross,expenses,profit,first,last).allMatch(Objects::nonNull);
        return new Totals(city,base,manual,gross,expenses,profit,
                first,last,first==null||last==null?null:last.subtract(first),
                complete);
    }
    private static CityResult city(Revision revision,String city) { return revision.report()==null||revision.report().cities()==null?null:
            revision.report().cities().stream().filter(c->city.equals(c.city())).findFirst().orElse(null); }
    private static InventoryResult inventory(Revision revision,String city) { return revision.report()==null||revision.report().inventory()==null?null:
            revision.report().inventory().stream().filter(i->city.equals(i.city())).findFirst().orElse(null); }
    private static BigDecimal sum(List<CityResult> rows,Function<CityResult,BigDecimal> field) {
        BigDecimal total=BigDecimal.ZERO;
        for(var row:rows) { if(row==null||field.apply(row)==null) return null; total=total.add(field.apply(row)); }
        return total;
    }
}
