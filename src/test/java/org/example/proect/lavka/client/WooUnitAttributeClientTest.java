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
