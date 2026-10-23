package org.example.proect.lavka.service;

import org.example.proect.lavka.client.*;
import org.example.proect.lavka.dao.wp.WpProductDao;
import org.example.proect.lavka.dto.*;
import org.example.proect.lavka.property.WooProperties;
import org.example.proect.lavka.service.sync.SyncOutcomeUnknownException;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncDurableChunkTest {
    WpProductDao wp=mock(WpProductDao.class);
    CardTovExportService cards=mock(CardTovExportService.class);
    WooApiClient woo=mock(WooApiClient.class);
    LavkaLocationsClient locations=mock(LavkaLocationsClient.class);
    SyncServiceImpl sync=new SyncServiceImpl(wp,cards,woo,locations,new WooProperties());
    @BeforeEach void setup() {
        when(woo.withoutWriteRetries(any())).thenAnswer(i -> ((Supplier<?>)i.getArgument(0)).get());
        when(woo.requireUnitAttributeId()).thenReturn(87L);
        when(woo.upsertProductsBatchOnce(any())).thenReturn(new WooApiClient.WooBatchResult(0,1,0));
        when(wp.collectSeenWindow(anyInt(),any())).thenReturn(List.of(new SeenItem("P-1","old",100L)));
        when(woo.readProductAttributes(any())).thenReturn(Map.of(100L,List.of()));
        when(cards.diffPage(any(),anyInt(),anyList(),anyBoolean())).thenReturn(new CardTovExportService.DiffResult(
                "P-1",true,List.of(SyncServiceUnitAttributeTest.dto("P-1","80ml")),List.of(),List.of()));
    }
    @Test void boundedWindowAndWritesNeverUseLegacyRetryPath() {
        var result=sync.runDurableChunk(50,"!",false,false);
        verify(wp).collectSeenWindow(51,"!"); verify(woo).upsertProductsBatchOnce(any());
        verify(woo,never()).upsertProductsBatch(any()); assertThat(result.exhausted()).isFalse();
    }
    @Test void emptyWooStillReadsAndCreatesFolioTail() {
        when(wp.collectSeenWindow(anyInt(),any())).thenReturn(List.of());
        when(cards.diffPage(any(),anyInt(),anyList(),anyBoolean())).thenReturn(new CardTovExportService.DiffResult(
                "Z",true,List.of(),List.of(),List.of(SyncServiceUnitAttributeTest.dto("Z","80ml"))));
        var result=sync.runDurableChunk(50,"P-1",false,false);
        assertThat(result.exhausted()).isTrue(); verify(woo).upsertProductsBatchOnce(any());
    }
    @Test void transportAndTruncatedReceiptsAreNeverAutomaticallyReplayed() {
        when(woo.upsertProductsBatchOnce(any())).thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));
        assertThatThrownBy(() -> sync.runDurableChunk(50,"!",false,false)).isInstanceOf(SyncOutcomeUnknownException.class);
        verify(woo,times(1)).upsertProductsBatchOnce(any());
        doReturn(new WooApiClient.WooBatchResult(0,0,0,List.of("WOO_BATCH_RESPONSE_INCOMPLETE: update"))).when(woo).upsertProductsBatchOnce(any());
        assertThatThrownBy(() -> sync.runDurableChunk(50,"!",false,false)).isInstanceOf(SyncOutcomeUnknownException.class);
    }
    @Test void explicitItemFailureIsReportedWithoutBlockingOtherItems() {
        when(woo.upsertProductsBatchOnce(any())).thenReturn(new WooApiClient.WooBatchResult(0,0,0,List.of("WOO_BATCH_ITEM_FAILED: update[0] invalid_sku")));
        assertThat(sync.runDurableChunk(50,"!",false,false).errors()).hasSize(1);
    }
    @Test void paddedWooSkuUpdatesExistingIdWithoutCreatingDuplicate() {
        when(wp.collectSeenWindow(anyInt(),any())).thenReturn(List.of(new SeenItem(" P-1 ","old",100L)));
        sync.runDurableChunk(50,"!",false,true);
        var capture=org.mockito.ArgumentCaptor.forClass(Map.class); verify(woo).upsertProductsBatchOnce(capture.capture());
        assertThat(capture.getValue().toString()).contains("id=100").doesNotContain("create=[{");
    }
    @Test void dryRunDoesNotPostProductsOrDescriptions() {
        sync.runDurableChunk(1,"!",true,true);
        verify(woo,never()).upsertProductsBatchOnce(any()); verifyNoInteractions(locations);
    }
}
