package org.example.proect.lavka.service.folio;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FolioInternalTransferReservationTest {
    private boolean eligible(String line, String header, String operation, boolean accounted, boolean headerAccounted, boolean returned, String quantity) {
        return FolioProductMovementClassifier.isInternalTransferReservation(line, header, operation, accounted, headerAccounted, returned, new BigDecimal(quantity));
    }
    @Test void onlyActivePositiveInternalAccountLinesAreEligible() {
        assertThat(eligible("С", "С", "*ПЕРЕМЕЩЕНИЕ", true, true, false, "6")).isTrue();
        assertThat(eligible("С", "С", "*ПРЕДОПЛАТ", true, true, false, "3")).isFalse();
        assertThat(eligible("Р", "Р", "*ПЕРЕМЕЩЕНИЕ", true, true, false, "6")).isFalse();
        assertThat(eligible("С", "Р", "*ПЕРЕМЕЩЕНИЕ", true, true, false, "6")).isFalse();
        assertThat(eligible("С", "С", "*ПЕРЕМЕЩЕНИЕ", false, true, false, "6")).isFalse();
        assertThat(eligible("С", "С", "*ПЕРЕМЕЩЕНИЕ", true, false, false, "6")).isFalse();
        assertThat(eligible("С", "С", "*ПЕРЕМЕЩЕНИЕ", true, true, true, "6")).isFalse();
        assertThat(eligible("С", "С", "*ПЕРЕМЕЩЕНИЕ", true, true, false, "0")).isFalse();
        assertThat(eligible("С", "С", "*ПЕРЕМЕЩЕНИЕ", true, true, false, "-6")).isFalse();
    }
    @Test void reservationRemainsSeparateFromSalesAndPhysicalMovements() {
        var c = FolioProductMovementClassifier.classify("С", "С", "*ПЕРЕМЕЩЕНИЕ", "Я", true, false);
        assertThat(c.movementClass()).isEqualTo("RESERVATION");
        assertThat(c.affectsStock()).isFalse();
        assertThat(c.affectsFinancialSales()).isFalse();
        assertThat(c.affectsPlanningDemand()).isFalse();
    }
}
