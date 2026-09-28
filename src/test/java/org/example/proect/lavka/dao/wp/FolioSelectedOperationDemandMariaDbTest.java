package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** Uses the existing disposable loopback fixture, never a Folio/production database. */
class FolioSelectedOperationDemandMariaDbTest extends FolioAvailabilityMariaDbTest {
    private int recno;
    private void movement(String kind, String document, String direction, String mode,
                          int affectsStock, int planning, int financial, int units, int day) {
        jdbc.update("""
            INSERT INTO folio_product_movement_fact
            (source_database,warehouse_id,movement_recno,generation_id,document_date,document_type,
             sku,quantity,signed_quantity,movement_class,stock_direction,demand_mode,payment_terms,
             customer_segment,supplier_state,affects_stock,affects_planning_demand,
             affects_financial_sales,operation_kind,sale_amount,accounting_value,captured_at)
            VALUES ('Fixture',1,?,1,?,?,'SKU-0',?,?,'OTHER',?,?,'UNKNOWN','UNKNOWN','CURRENT',?,?,?,?,?,0,NOW())
            """, ++recno,START.plusDays(day-1),document,units,-units,direction,mode,
            affectsStock,planning,financial,kind,units);
    }
    private QuerySpec selected(Map<String,Selection> filters, List<Integer> stockOnly) {
        return new QuerySpec("Fixture",List.of(1,7),START,START.plusDays(29),null,
                Map.of("skus",new Selection("INCLUDE",List.of("SKU-0"))),filters,
                50,0,List.of(),"SOLD_UNITS",null,stockOnly);
    }
    @Test void selectedConsumptionChangesDemandAndLostDemandButNotFinancialSales() {
        movement("*ПРЕДОПЛАТА","Р","OUT","REGULAR",1,1,1,85,3);
        movement("*ПРЕДОПЛАТ","Р","OUT","REGULAR",1,1,1,4,23);
        movement("РАСХОДНИКИ","Р","OUT","NOT_APPLICABLE",1,0,0,19,5);
        movement("МУЛЬТИСБОРКА","Р","OUT","NOT_APPLICABLE",1,0,0,6,5);
        movement("*ПЕРЕМЕЩЕНИЕ","Р","OUT","NOT_APPLICABLE",1,0,0,1000,5);
        movement("*РАЗОВАЯ","Р","OUT","ONE_OFF_ORDER",1,0,1,1000,5);
        movement("РАСХОДНИКИ","С","OUT","NOT_APPLICABLE",0,0,0,1000,5);
        movement("РАСХОДНИКИ","П","IN","NOT_APPLICABLE",1,0,0,1000,5);
        movement("РАСХОДНИКИ","Р","OUT","NOT_APPLICABLE",0,0,0,1000,5);
        var kinds=new Selection("INCLUDE",List.of("*ПРЕДОПЛАТА","*ПРЕДОПЛАТ","РАСХОДНИКИ","МУЛЬТИСБОРКА","*ПЕРЕМЕЩЕНИЕ","*РАЗОВАЯ"));
        var spec=selected(Map.of("operationKinds",kinds),List.of());
        var result=dao.query(spec);
        assertThat(result.rows().get(0).metrics().regularSoldUnits()).isEqualByComparingTo("114");
        assertThat(result.total().metrics().regularSoldUnits()).isEqualByComparingTo("114");
        assertThat(result.rows().get(0).metrics().soldUnits()).isEqualByComparingTo("1089");
        assertThat(result.rows().get(0).metrics().regularRevenue()).isEqualByComparingTo("89");
        assertThat(dao.salesOnAvailableDays(spec,List.of(1),List.of("SKU-0")).get("SKU-0")).isEqualByComparingTo("110");
        assertThat(dao.salesOnAvailableDays(spec,List.of(1,7),List.of("SKU-0")).get("SKU-0")).isEqualByComparingTo("114");
        // An intentional demand-mode restriction still narrows the selected operations.
        var regular=selected(Map.of("operationKinds",kinds,"demandModes",new Selection("INCLUDE",List.of("REGULAR"))),List.of());
        assertThat(dao.query(regular).rows().get(0).metrics().regularSoldUnits()).isEqualByComparingTo("89");
        // An absent selection and EXCLUDE selection do not opt into consumption demand.
        for(var filters:List.of(Map.<String,Selection>of(),Map.of("operationKinds",new Selection("EXCLUDE",List.of("*ПЕРЕМЕЩЕНИЕ")))))
            assertThat(dao.query(selected(filters,List.of())).rows().get(0).metrics().regularSoldUnits()).isEqualByComparingTo("89");
        assertThat(dao.query(selected(Map.of("operationKinds",kinds),List.of(1))).rows().get(0).metrics().regularSoldUnits()).isZero();
    }
}
