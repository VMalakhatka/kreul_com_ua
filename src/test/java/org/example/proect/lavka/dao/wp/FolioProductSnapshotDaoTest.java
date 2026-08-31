package org.example.proect.lavka.dao.wp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FolioProductSnapshotDaoTest {

    @Test
    void decimalValuesAreRoundedToThePublishedMariaDbScale() {
        assertThat(FolioProductSnapshotDao.scaled(new BigDecimal("86.09123456"), 4))
                .isEqualByComparingTo("86.0912");
        assertThat(FolioProductSnapshotDao.scaled(new BigDecimal("1.23456789"), 6))
                .isEqualByComparingTo("1.234568");
        assertThat(FolioProductSnapshotDao.scaled(new BigDecimal("-1.005"), 2))
                .isEqualByComparingTo("-1.01");
    }
}
