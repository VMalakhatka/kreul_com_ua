package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioAccountDao;
import org.example.proect.lavka.dto.folio.*;
import org.example.proect.lavka.controller.FolioManagerOrderController;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerStockPoolTest {
    final FolioAccountDao dao=mock(FolioAccountDao.class);
    final FolioAccountService accounts=mock(FolioAccountService.class);
    final FolioOrderAccountService service=new FolioOrderAccountService(accounts,dao);
    final ManagerStockPool pool=new ManagerStockPool(7,List.of(9));
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private BigDecimal n(String value){return new BigDecimal(value);}
    private FolioAccountDao.StockRow row(int id,String free){return new FolioAccountDao.StockRow("SKU",id,n("10"),n(free),n("2"),n("0"),"",n("0"),n("0"),n("0"),n("0"),n("0"));}
    private void stock(int id,String free){when(dao.findStock("SKU",id)).thenReturn(Optional.of(row(id,free)));when(dao.lockStock("SKU",id)).thenReturn(Optional.of(row(id,free)));}
    private FolioOrderAccountRequest request(boolean preview) throws Exception {
        return json.readValue("""
            {"preview_only":%s,"schema_version":"folio-order-preview/v1","woo_order":{"id":123,"status":"on-hold"},
             "folio_account_header":{"externalRequestId":"test-pool","documentNumber":"500","documentDate":"2026-10-27T00:00:00","warehouseId":4,
               "operationType":"*РАЗОВАЯ","controlDate":"2026-10-30","folioOperationKind":"СЧЕТ","payerName":"TEST","receiverName":"TEST",
               "payerShortName":"TEST","folioUser":"TEST","sourceInfo":"Int 123","accountingEnabled":true},
             "items":[{"sku":"SKU","quantity":8,"unit_price":2,"allocations":[{"quantity":8,"folio_warehouses":[{"id":"7","priority":0}]}]}]}
            """.formatted(preview),FolioOrderAccountRequest.class);
    }
    @Test void previewCountsSignedTargetDebtWithoutLocksOrWrites() throws Exception {
        stock(7,"-3");stock(9,"10");
        var result=service.createManagerOrder(request(true),pool);
        assertThat(result.documents()).hasSize(2);
        assertThat(result.documents().get(0).items().get(0).quantity()).isEqualByComparingTo("7");
        assertThat(result.documents().get(1).items().get(0).quantity()).isEqualByComparingTo("1");
        assertThat(result.documents().get(1).folioWarehouseId()).isEqualTo(4);
        verify(dao,never()).lockStock(anyString(),anyInt());
        verify(dao,never()).findDocumentIdByExternalRequestId(anyString());
        verifyNoInteractions(accounts);
    }
    @Test void ordinaryCheckoutDoesNotUseAuxiliaryStock() throws Exception {
        stock(7,"-3");stock(9,"10");
        var result=service.createFromWooOrder(request(true));
        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).accountingEnabled()).isFalse();
        assertThat(result.documents().get(0).items().get(0).quantity()).isEqualByComparingTo("8");
        verify(dao,never()).findStock("SKU",9);
    }
    @Test void targetStockCardMustExistAndCombinedTotalCannotBeNegative(){
        stock(9,"10"); assertThat(pool.available(dao,"SKU",false)).isZero();
        stock(7,"-12"); assertThat(pool.available(dao,"SKU",false)).isZero();
    }
    @Test void reserveOnlyUpdatesTargetAndLocksInStableOrder(){
        stock(7,"-3");stock(9,"10");when(dao.reserveUsingPool("SKU",7,n("7"),n("10"))).thenReturn(1);
        pool.reserve(dao,"SKU",n("7"));
        var ordered=inOrder(dao);ordered.verify(dao).lockStock("SKU",7);ordered.verify(dao).lockStock("SKU",9);
        ordered.verify(dao).reserveUsingPool("SKU",7,n("7"),n("10"));verifyNoMoreInteractions(dao);
    }
    @Test void stockChangesAndFailedConditionalReserveReject(){
        stock(7,"-3");stock(9,"10");
        assertThatThrownBy(()->pool.requireAvailable(dao,"SKU",n("8"))).isInstanceOf(FolioAccountConflictException.class);
        assertThatThrownBy(()->pool.reserve(dao,"SKU",n("7"))).isInstanceOf(FolioAccountConflictException.class);
    }
    @Test void validatesPoolAndRejectsOverlappingDestination() throws Exception {
        assertThatThrownBy(()->new ManagerStockPool(7,List.of(7))).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(()->new ManagerStockPool(7,List.of(9,9))).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(()->new ManagerStockPool(7,List.of())).isInstanceOf(FolioAccountValidationException.class);
        var request=request(true);
        assertThatThrownBy(()->new ManagerStockPool(9,List.of(7)).validate(request)).isInstanceOf(FolioAccountValidationException.class);
    }
    @Test void applyUsesPoolAndDurablyClaimsWholeOperation() throws Exception {
        stock(7,"0");stock(9,"10");
        var response=json.readValue("{\"documentId\":1001,\"documentNumber\":\"500\",\"warehouseId\":7,\"accountingEnabled\":true}",FolioAccountResponse.class);
        when(accounts.createUsingPool(any(),eq(pool))).thenReturn(response);
        var result=service.createManagerOrder(request(false),pool);
        assertThat(result.documents()).hasSize(1);
        verify(accounts).createUsingPool(argThat(r->r.warehouseId()==7 && r.items().get(0).quantity().compareTo(n("8"))==0),eq(pool));
        verify(accounts,never()).create(any());verify(dao).rememberExternalRequest("test-pool",1001L);
        when(dao.findDocumentIdByExternalRequestId("test-pool")).thenReturn(Optional.of(1001L));
        assertThatThrownBy(()->service.createManagerOrder(request(false),pool)).isInstanceOf(FolioAccountConflictException.class);
        verify(accounts,times(1)).createUsingPool(any(),any());
    }
    @Test void privateRouteRejectsMissingAndWrongCredentials() throws Exception {
        var props=mock(LavkaApiProperties.class);var svc=mock(FolioOrderAccountService.class);
        var controller=new FolioManagerOrderController(svc,props);var cmd=new FolioManagerOrderController.Command(request(true),pool);
        assertThat(controller.create(cmd,null).getStatusCode().value()).isEqualTo(503);
        when(props.getToken()).thenReturn("test-only");
        assertThat(controller.create(cmd,null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.create(cmd,"wrong").getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(svc);
        var response=service.createManagerOrder(request(true),pool);when(svc.createManagerOrder(any(),any())).thenReturn(response);
        assertThat(controller.create(cmd,"test-only").getStatusCode().value()).isEqualTo(200);
    }

    @Test void readyAndWaitingAreSeparateAccountsOnSameWarehouse() throws Exception {
        stock(7,"3");stock(9,"5");
        var result=service.createManagerOrder(request(true),pool);
        assertThat(result.documents()).extracting(FolioOrderAccountResponse.Document::documentType)
                .containsExactly("account","waiting_transfer_account");
        assertThat(result.documents()).allMatch(d -> d.accountingEnabled() && d.folioWarehouseId()==7);
        assertThat(result.documents().get(0).items().get(0).quantity()).isEqualByComparingTo("3");
        assertThat(result.documents().get(1).items().get(0).quantity()).isEqualByComparingTo("5");
        assertThat(result.documents().get(1).items().get(0).allocationStatus()).isEqualTo("awaiting_transfer");
        assertThat(result.documents().get(0).sourceExternalRequestId()).isNotEqualTo(result.documents().get(1).sourceExternalRequestId());
        verifyNoInteractions(accounts);
    }
    @Test void readyWaitingOtherWarehouseAndShortageConserveQuantity() throws Exception {
        stock(7,"1");stock(9,"2");stock(8,"3");
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(request(true));
        ((com.fasterxml.jackson.databind.node.ArrayNode)tree.at("/items/0/allocations/0/folio_warehouses"))
                .addObject().put("id","8").put("priority",1);
        var result=service.createManagerOrder(json.treeToValue(tree,FolioOrderAccountRequest.class),pool);
        assertThat(result.documents()).extracting(FolioOrderAccountResponse.Document::documentType)
                .containsExactly("account","account","waiting_transfer_account","missing_stock_account");
        assertThat(result.documents().stream().flatMap(d->d.items().stream()).map(FolioOrderAccountResponse.Item::quantity)
                .reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("8");
        assertThat(result.documents().get(3).items().get(0).quantity()).isEqualByComparingTo("2");
    }
    @Test void duplicateSkuCannotReuseAuxiliaryStock() throws Exception {
        stock(7,"3");stock(9,"5");
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(request(true));
        var items=(com.fasterxml.jackson.databind.node.ArrayNode)tree.get("items"); items.add(items.get(0).deepCopy());
        var result=service.createManagerOrder(json.treeToValue(tree,FolioOrderAccountRequest.class),pool);
        assertThat(result.documents()).hasSize(3);
        assertThat(result.documents().get(2).items().get(0).quantity()).isEqualByComparingTo("8");
    }
    @Test void applyReservesReadyNormallyThenWaitingWithExplicitMarker() throws Exception {
        stock(7,"3");stock(9,"5");
        var response=json.readValue("{\"documentId\":1001,\"documentNumber\":\"500\",\"warehouseId\":7,\"accountingEnabled\":true}",FolioAccountResponse.class);
        when(accounts.create(any())).thenReturn(response);when(accounts.createUsingPool(any(),eq(pool))).thenReturn(response);
        service.createManagerOrder(request(false),pool);
        var ordered=inOrder(accounts);
        ordered.verify(accounts).create(argThat(r->r.items().get(0).quantity().compareTo(n("3"))==0));
        ordered.verify(accounts).createUsingPool(argThat(r->r.items().get(0).quantity().compareTo(n("5"))==0
                && r.sourceInfo().equals("Ожидает перемещения") && r.additionalInfo().startsWith("ОЖП")),eq(pool));
    }
}
