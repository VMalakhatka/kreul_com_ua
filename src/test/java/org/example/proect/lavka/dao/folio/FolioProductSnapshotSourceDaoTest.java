package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.service.folio.FolioAccountingModeUnsupportedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolioProductSnapshotSourceDaoTest {

    @Test
    void acceptsBothVerifiedAverageModes() {
        FolioProductSnapshotSourceDao.validateAccountingMode(warehouse("1000"));
        FolioProductSnapshotSourceDao.validateAccountingMode(warehouse("1100"));
    }

    @Test
    void unsupportedModeContainsActionableDiagnostics() {
        assertThatThrownBy(() -> FolioProductSnapshotSourceDao
                .validateAccountingMode(warehouse("1001")))
                .isInstanceOfSatisfying(
                        FolioAccountingModeUnsupportedException.class,
                        error -> {
                            assertThat(error.getCode())
                                    .isEqualTo("PRODUCT_SNAPSHOT_ACCOUNTING_MODE_UNSUPPORTED");
                            assertThat(error.rawCode()).isEqualTo(1001);
                            assertThat(error.modeName()).isEqualTo("LIFO");
                            assertThat(error.recommendation()).contains("Exclude this warehouse");
                        });
    }

    private static FolioProductSnapshotSourceDao.Warehouse warehouse(String rawCode) {
        return new FolioProductSnapshotSourceDao.Warehouse(
                "Paint_Rus", 22, "Деливери Ялта", new BigDecimal(rawCode), null);
    }
}
