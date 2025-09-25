package org.example.proect.lavka.client;

import org.example.proect.lavka.dto.stock.LocMap;
import org.example.proect.lavka.dto.stock.LocationsResponse;
import org.example.proect.lavka.dto.stock.WooLocation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class LavkaLocationsClient {

    private final RestTemplate rt;
    private final String base;

    public LavkaLocationsClient(@Qualifier("lavkaRestTemplateBasic")RestTemplate lavkaRestTemplate,
                                @Value("${lavka.apiBase}") String base) {
        this.rt = lavkaRestTemplate;
        this.base = base.endsWith("/") ? base.substring(0, base.length()-1) : base;
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
}