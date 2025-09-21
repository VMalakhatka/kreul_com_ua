package org.example.proect.lavka.service.woo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Service
public class WooProductsService {

    private final RestTemplate rt;
    private final String baseUrl;
    private final int perPage;

    public WooProductsService(RestTemplate wooRestTemplate,
                              @Value("${woocommerce.baseUrl}") String baseUrl,
                              @Value("${woocommerce.perPage:100}") int perPage) {
        this.rt = wooRestTemplate;
        this.baseUrl = baseUrl;
        this.perPage = Math.min(Math.max(perPage, 1), 100);
    }

    public static class WcProduct {
        public Long id;
        public String sku;
        public Boolean manage_stock;
        public Integer stock_quantity;
        public String type;
    }

    /** Загружаем ВСЕ продукты (postранично) и строим sku -> productId */
    public Map<String, Long> buildSkuIndex() {
        Map<String, Long> map = new HashMap<>();
        int page = 1;
        while (true) {
            String url = UriComponentsBuilder
                    .fromHttpUrl(baseUrl + "/products")
                    .queryParam("per_page", perPage)
                    .queryParam("page", page)
                    .build(true).toUriString();
            ResponseEntity<WcProduct[]> resp = rt.getForEntity(url, WcProduct[].class);
            WcProduct[] arr = resp.getBody();
            if (arr == null || arr.length == 0) break;
            for (WcProduct p : arr) {
                if (p.id != null && p.sku != null && !p.sku.isBlank()) {
                    map.put(p.sku, p.id);
                }
            }
            page++;
        }
        return map;
    }

    /** Батч-обновление stock_quantity по productId. Отправляем пачками по <=100 */
    public void batchUpdateStocks(Map<Long, Integer> idToQty) {
        List<Map<String, Object>> buffer = new ArrayList<>(100);
        for (var e : idToQty.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getKey());
            item.put("manage_stock", true); // на всякий случай включим
            item.put("stock_quantity", e.getValue());
            buffer.add(item);
            if (buffer.size() == 100) { sendBatch(buffer); buffer.clear(); }
        }
        if (!buffer.isEmpty()) sendBatch(buffer);
    }

    private void sendBatch(List<Map<String, Object>> updateItems) {
        String url = baseUrl + "/products/batch";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("update", updateItems);
        rt.exchange(url, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    /** (Опционально) батч-установка SKU по productId */
    public void batchUpdateSkus(Map<Long, String> idToSku) {
        List<Map<String, Object>> buffer = new ArrayList<>(100);
        for (var e : idToSku.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getKey());
            item.put("sku", e.getValue());
            buffer.add(item);
            if (buffer.size() == 100) { sendBatch(buffer); buffer.clear(); }
        }
        if (!buffer.isEmpty()) sendBatch(buffer);
    }
}
