package org.example.proect.lavka.controller;

import org.example.proect.lavka.dto.stock.MsWarehouse;
import org.example.proect.lavka.service.stock.MsWarehouseService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@CrossOrigin(origins = {"http://paint.local", "https://kreul.com.ua"}, allowCredentials = "true")
public class MsRefController {
    private final MsWarehouseService service;
    public MsRefController(MsWarehouseService service) { this.service = service; }

    @GetMapping(value="/ref/warehouses", produces="application/json;charset=UTF-8")
    public List<MsWarehouse> listWarehouses() {
        return service.fetchVisible();
    }
}