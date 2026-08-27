package org.example.proect.lavka.service.folio;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FolioAccountingPriceRuntimeMonitorTest {

    @Test
    void tracksExactStageAndClearsItWhenJobFinishes() {
        FolioAccountingPriceRuntimeMonitor monitor =
                new FolioAccountingPriceRuntimeMonitor(
                        mock(DataSource.class), mock(DataSource.class), true, 120);

        monitor.start("job-1", "Paint_Ua", 15, "native-range", false);
        monitor.checkpoint(
                "job-1", "APPLY_RUNNING", "FOLIO_PROCEDURE_CALL",
                "KR-84127", 125, 500, 126, 124);

        FolioAccountingPriceRuntimeMonitor.Checkpoint checkpoint =
                monitor.currentCheckpoint();
        assertThat(checkpoint).isNotNull();
        assertThat(checkpoint.phase()).isEqualTo("APPLY_RUNNING");
        assertThat(checkpoint.stage()).isEqualTo("FOLIO_PROCEDURE_CALL");
        assertThat(checkpoint.sku()).isEqualTo("KR-84127");
        assertThat(checkpoint.processed()).isEqualTo(125);
        assertThat(checkpoint.procedureCalls()).isEqualTo(126);
        assertThat(checkpoint.committedChunks()).isEqualTo(124);

        monitor.finish("job-1", "COMPLETED", null);

        assertThat(monitor.currentCheckpoint()).isNull();
    }
}
