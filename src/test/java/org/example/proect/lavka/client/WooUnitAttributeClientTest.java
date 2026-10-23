package org.example.proect.lavka.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.client.support.RetryingRestExecutor;
import org.example.proect.lavka.property.WooProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WooUnitAttributeClientTest {
    MockRestServiceServer server; WooApiClient client;
    static final String BASE="https://woo.example.invalid/wp-json/wc/v3";
    @BeforeEach void setup() {
        var rest=new RestTemplate(); server=MockRestServiceServer.bindTo(rest).build();
        var props=new WooProperties(); props.setBaseUrl(BASE);
        client=new WooApiClient(rest,props,new RetryingRestExecutor());
    }
    @Test void resolvesByExactSlugNotNameOrHardcodedId() {
        server.expect(requestTo(BASE+"/products/attributes")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":2,\"name\":\"Одиниця виміру\",\"slug\":\"pa_wrong\"},"
                        +"{\"id\":901,\"name\":\"Одиниця виміру\",\"slug\":\"pa_edin_izmer\"}]",MediaType.APPLICATION_JSON));
        assertThat(client.requireUnitAttributeId()).isEqualTo(901); server.verify();
    }
    @Test void durableProductPostDoesNotRetryHttp500() {
        server.expect(requestTo(BASE+"/products/batch")).andRespond(withServerError());
        assertThatThrownBy(() -> client.upsertProductsBatchOnce(Map.of("update",List.of(Map.of("id",100)))))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        server.verify();
    }
    @Test void durableCategoryWriteDoesNotRetryTimeout() {
        server.expect(requestTo(BASE+"/products/categories")).andRespond(withException(new java.net.SocketTimeoutException()));
        assertThatThrownBy(() -> client.withoutWriteRetries(() -> client.createCategory("Synthetic","synthetic",0L)))
                .isInstanceOf(org.example.proect.lavka.service.sync.SyncOutcomeUnknownException.class);
        server.verify();
    }
    @Test void malformedItemReceiptIsUnknownNotAnExplicitRejection() {
        server.expect(requestTo(BASE+"/products/batch")).andRespond(withSuccess("{\"update\":[{}]}",MediaType.APPLICATION_JSON));
        assertThat(client.upsertProductsBatchOnce(Map.of("update",List.of(Map.of("id",100)))).errors())
                .containsExactly("WOO_BATCH_RESPONSE_INVALID: update[0] INVALID_RESULT");
        server.verify();
    }
    @Test void supplierSupportUsesReadOnlySchemaAndFailsClosedOnOldPlugin() {
        server.expect(requestTo(BASE+"/products")).andExpect(method(HttpMethod.OPTIONS))
                .andRespond(withSuccess("{\"schema\":{\"properties\":{}}}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::requireFullSyncSupplierSupport).hasMessageContaining("WOO_FULL_SYNC_SUPPLIER_UNSUPPORTED");
        server.verify();
    }
    @Test void acceptsSupplierContract() {
        server.expect(requestTo(BASE+"/products")).andExpect(method(HttpMethod.OPTIONS))
                .andRespond(withSuccess("{\"schema\":{\"properties\":{\"lavka_supplier\":{\"type\":\"string\",\"x-lavka-full-sync-supplier\":true}}}}",MediaType.APPLICATION_JSON));
        client.requireFullSyncSupplierSupport(); server.verify();
    }
    @Test void http200SupplierErrorIsNotCountedAsSuccessOrRetried() {
        server.expect(requestTo(BASE+"/products/batch")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"update\":[{\"id\":101},{\"id\":0,\"error\":{\"code\":\"lts_supplier_save_failed\",\"message\":\"private details\"}}]}",MediaType.APPLICATION_JSON));
        var result=client.upsertProductsBatch(Map.of("update",List.of(Map.of("id",101),Map.of("id",102))));
        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.errors()).containsExactly("WOO_BATCH_ITEM_FAILED: update[1] lts_supplier_save_failed");
        server.verify();
    }
    @Test void emptyBatchResponseIsNotSuccess() {
        server.expect(requestTo(BASE+"/products/batch")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        var result=client.upsertProductsBatch(Map.of("create",List.of(Map.of("sku","TEST"))));
        assertThat(result.createdCount()).isZero();
        assertThat(result.errors()).containsExactly("WOO_BATCH_RESPONSE_INCOMPLETE: create");
        server.verify();
    }
    @Test void missingAttributeExplicitlyFailsWithoutPost() {
        server.expect(requestTo(BASE+"/products/attributes")).andRespond(withSuccess("[]",MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::requireUnitAttributeId).hasMessageContaining("WOO_UNIT_ATTRIBUTE_MISSING"); server.verify();
    }
    @Test void reads101ProductsInTwoBatchesPreservingAttributeSettings() throws Exception {
        var ids=LongStream.rangeClosed(1,101).boxed().toList();
        var attrs=List.of(Map.of("id",87,"name","Одиниця виміру","position",6,"visible",false,
                "variation",true,"options",List.of("2  x  125мл")));
        for(int from=0;from<ids.size();from+=100) {
            var batch=ids.subList(from,Math.min(from+100,ids.size()));
            String included=batch.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE+"/products?")))
                    .andExpect(method(HttpMethod.GET)).andExpect(queryParam("include",included))
                    .andExpect(queryParam("per_page","100")).andExpect(queryParam("_fields","id,attributes"))
                    .andRespond(withSuccess(new ObjectMapper().writeValueAsString(
                            batch.stream().map(id->Map.of("id",id,"attributes",attrs)).toList()),MediaType.APPLICATION_JSON));
        }
        var found=client.readProductAttributes(ids);
        assertThat(found).hasSize(101);
        assertThat(found.get(101L).get(0)).containsEntry("options",List.of("2  x  125мл"))
                .containsEntry("visible",false).containsEntry("variation",true).containsEntry("position",6);
        server.verify();
    }
    @Test void incompleteProductReadIsNotAnEmptyAttributeCollection() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE+"/products?")))
                .andRespond(withSuccess("[]",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.readProductAttributes(List.of(100L))).hasMessage("WOO_PRODUCT_ATTRIBUTES_INCOMPLETE");
        server.verify();
    }
    @Test void missingAttributesFieldIsNotAnEmptyAttributeCollection() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE+"/products?")))
                .andRespond(withSuccess("[{\"id\":100}]",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.readProductAttributes(List.of(100L))).hasMessage("WOO_PRODUCT_ATTRIBUTES_INVALID");
        server.verify();
    }
}
