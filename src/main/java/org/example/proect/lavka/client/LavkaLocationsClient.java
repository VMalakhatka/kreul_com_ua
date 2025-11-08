package org.example.proect.lavka.client;

import org.example.proect.lavka.dto.stock.LocMap;
import org.example.proect.lavka.dto.stock.LocationsResponse;
import org.example.proect.lavka.dto.stock.WooLocation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LavkaLocationsClient {

    private final RestTemplate rt;
    private final String base;

    public LavkaLocationsClient(@Qualifier("lavkaRestTemplateBasic")RestTemplate lavkaRestTemplate,
                                @Value("${lavka.apiBase}") String base) {
        this.rt = lavkaRestTemplate;
        this.base = base.endsWith("/") ? base.substring(0, base.length()-1) : base;
    }


    public record MediaLinkOnlyPayload(
            long product_id,
            String s3_key,
            String url,
            String mime,
            boolean set_featured,
            boolean add_to_gallery,
            int gallery_position
    ) {}

    @SuppressWarnings("unchecked")
    public Map<String,Object> mediaLinkOnly(MediaLinkOnlyPayload p) {
        String url = base + "/media/link-only"; // base уже = .../wp-json/lavka/v1
        return rt.postForObject(url, p, Map.class);
    }


    public List<WooLocation> listLocations() {
        String url = base + "/locations";
        try {
            var resp = rt.getForEntity(url, LocationsResponse.class);
            var body = resp.getBody();
            return (body == null || body.items() == null) ? List.of() : body.items();
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // 4xx/5xx от Woo (покажет статус и тело ошибки)
            throw new IllegalStateException("Lavka /locations failed: " + e.getRawStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Lavka /locations failed: " + e.getMessage(), e);
        }
    }

    public LocMap getMap() {
        String url = base + "/locations/map";
        try {
            var resp = rt.getForEntity(url, LocMap.class);
            return resp.getBody() != null ? resp.getBody() : new LocMap(List.of());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new IllegalStateException("Lavka /locations/map failed: " + e.getRawStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Lavka /locations/map failed: " + e.getMessage(), e);
        }
    }

    public void saveMap(LocMap map) {
        var url = base + "/locations/map";
        rt.put(url, map); // 2xx == OK
    }

    public void pushCategoryDescriptionsBatch(Map<Long,String> catMap) {
        // собрать payload в формате:
        // { "items": [ { "term_id": 1504, "html": "<p>...</p>" }, ... ] }

        List<Map<String,Object>> items = new ArrayList<>();
        for (var e : catMap.entrySet()) {
            Long termId = e.getKey();
            String html = e.getValue();
            if (termId == null || termId <= 0) continue;
            if (html == null || html.isBlank()) continue;

            Map<String,Object> one = new HashMap<>();
            one.put("term_id", termId);
            one.put("html", html);
            items.add(one);
        }

        if (items.isEmpty()) {
            return; // нечего пушить — выходим тихо
        }

        Map<String,Object> payload = new HashMap<>();
        payload.put("items", items);

        String url = base + "/catdesc/batch";

        try {
            // lavkaRestTemplateBasic уже должен ставить Basic Auth заголовок
            rt.postForEntity(url, payload, Void.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // сюда попадёт 401, 403, 500 с телом
            throw new IllegalStateException(
                    "Lavka /catdesc/batch failed: " +
                            e.getRawStatusCode() + " " + e.getResponseBodyAsString(), e
            );
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Lavka /catdesc/batch failed: " + e.getMessage(), e
            );
        }
    }
}