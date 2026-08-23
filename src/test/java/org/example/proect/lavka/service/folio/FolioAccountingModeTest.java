package org.example.proect.lavka.service.folio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FolioAccountingModeTest {

    @Test
    void decodesAverageWithoutTax() {
        var mode = FolioAccountingMode.decode(1000);

        assertThat(mode.name()).isEqualTo("AVERAGE");
        assertThat(mode.calculationMode()).isZero();
        assertThat(mode.periodMode()).isZero();
        assertThat(mode.includeTax()).isFalse();
        assertThat(FolioAccountingMode.supportsProductSnapshot(1000)).isTrue();
        assertThat(FolioAccountingMode.supportsSafeNativeRecalculation(1000)).isTrue();
    }

    @Test
    void decodesAverageWithTax() {
        var mode = FolioAccountingMode.decode(1100);

        assertThat(mode.name()).isEqualTo("AVERAGE");
        assertThat(mode.calculationMode()).isZero();
        assertThat(mode.periodMode()).isZero();
        assertThat(mode.includeTax()).isTrue();
        assertThat(FolioAccountingMode.supportsProductSnapshot(1100)).isTrue();
        assertThat(FolioAccountingMode.supportsSafeNativeRecalculation(1100)).isTrue();
    }

    @Test
    void rejectsModesWithoutDedicatedGoldenMaster() {
        assertThat(FolioAccountingMode.supportsProductSnapshot(1001)).isFalse();
        assertThat(FolioAccountingMode.supportsProductSnapshot(1002)).isFalse();
        assertThat(FolioAccountingMode.supportsProductSnapshot(1005)).isFalse();
        assertThat(FolioAccountingMode.supportsSafeNativeRecalculation(null)).isFalse();
    }
}
