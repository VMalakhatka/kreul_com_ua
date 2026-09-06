package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Offline validation of a bounded, rollback-confirmed Paint_Rus SQL evidence file.
 * Does not connect to any database. Do not commit raw business evidence. */
@EnabledIfEnvironmentVariable(named = "FOLIO_AVAILABILITY_LAB_EVIDENCE", matches = ".+")
class FolioAvailabilityLabEvidenceTest {
    @Test void compareTenProductsAtTwoPhysicalWarehousesAgainstIndependentDailySql() throws Exception {
        JsonNode evidence = new ObjectMapper().readTree(Path.of(
                System.getenv("FOLIO_AVAILABILITY_LAB_EVIDENCE")).toFile());
        assertThat(evidence.path("database").asText()).isEqualTo("Paint_Rus");
        assertThat(evidence.path("state").asText()).isEqualTo("ROLLED_BACK");
        assertThat(evidence.path("transactionAfter").asInt()).isZero();
        var rowsets = new ArrayList<JsonNode>();
        for (JsonNode r : evidence.path("results")) if (r.path("kind").asText().equals("ROWSET")) rowsets.add(r.path("rows"));
        assertThat(rowsets).hasSize(3);
        assertThat(rowsets.get(0).size()).isEqualTo(20);
        assertThat(rowsets.get(2).size()).isEqualTo(600);
        Map<String, Map<LocalDate, BigDecimal>> deltas = new HashMap<>();
        for (JsonNode row : rowsets.get(1)) deltas.computeIfAbsent(key(row), k -> new HashMap<>())
                .merge(LocalDate.parse(row.get(2).asText()), new BigDecimal(row.get(3).asText()), BigDecimal::add);
        Map<String, FolioProductAvailabilityHistory.Month> masks = new HashMap<>();
        int reconciled = 0;
        for (JsonNode row : rowsets.get(0)) {
            var card = FolioProductAvailabilityHistoryTest.card(row.get(4).asText(), row.get(2).asText());
            var months = FolioProductAvailabilityHistory.build(card, deltas.getOrDefault(key(row), Map.of()),
                    LocalDate.of(2014,6,1), LocalDate.of(2014,7,1));
            var month = months.get(0);
            masks.put(key(row), month);
            if (month.quality().equals("RECONCILED")) reconciled++;
            else assertThat(month.knownMask()).isZero();
        }
        int checkedDays = 0;
        for (JsonNode row : rowsets.get(2)) {
            var month = masks.get(key(row));
            if (!month.quality().equals("RECONCILED")) continue;
            int day = LocalDate.parse(row.get(2).asText()).getDayOfMonth();
            boolean available = new BigDecimal(row.get(3).asText()).signum() > 0;
            assertThat((month.availableMask() & (1L << (day-1))) != 0).isEqualTo(available);
            checkedDays++;
        }
        assertThat(masks.keySet().stream().map(k -> k.substring(0,k.indexOf('|'))).distinct().count()).isEqualTo(10);
        assertThat(reconciled).isEqualTo(20);
        assertThat(checkedDays).isEqualTo(600);
    }
    private static String key(JsonNode row) { return row.get(0).asText()+"|"+row.get(1).asText(); }
}
