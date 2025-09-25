package org.example.proect.lavka.controller;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.client.LavkaLocationsClient;
import org.example.proect.lavka.dto.stock.LocMap;
import org.example.proect.lavka.dto.stock.WooLocation;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/stock/locations")
@RequiredArgsConstructor
public class LocationsAdminController {

    private final LavkaLocationsClient client;

    @GetMapping("/woo")
    public List<WooLocation> listWoo() {
        return client.listLocations();
    }

    @GetMapping("/map")
    public LocMap getMap() {
        return client.getMap();
    }

    @PutMapping("/map")
    public void putMap(@RequestBody LocMap map) {
        // опционально: валидация кодов (латиница, без пробелов)
        client.saveMap(map);
    }
}