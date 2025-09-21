package org.example.proect.lavka.service;


import org.example.proect.lavka.service.woo.WooProductsService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StockSyncService {

    private final StockQueryService db;
    private final WooProductsService woo;

    public StockSyncService(StockQueryService db, WooProductsService woo) {
        this.db = db;
        this.woo = woo;
    }

    /** Основной сценарий: по supplier/stockId подтянуть qty и обновить Woo */
    public Map<String, Object> syncBySupplierAndStock(String supplier, int stockId) {
        Map<String, Integer> skuQty = db.loadSkuQty(supplier, stockId);
        Map<String, Long> skuToId = woo.buildSkuIndex();

        Map<Long, Integer> idQty = new LinkedHashMap<>();
        List<String> notFoundSkus = new ArrayList<>();

        for (var e : skuQty.entrySet()) {
            Long id = skuToId.get(e.getKey());
            if (id != null) idQty.put(id, e.getValue());
            else notFoundSkus.add(e.getKey());
        }

        if (!idQty.isEmpty()) {
            woo.batchUpdateStocks(idQty);
        }

        return Map.of(
                "supplier", supplier,
                "stockId", stockId,
                "inputSkuCount", skuQty.size(),
                "updatedCount", idQty.size(),
                "notFoundSkus", notFoundSkus
        );
    }
    // DRY-RUN синка (ничего не пишет в Woo)
    public Map<String, Object> dryRunBySupplierAndStock(String supplier, int stockId) {
        Map<String, Integer> skuQty = db.loadSkuQty(supplier, stockId);
        Map<String, Long> skuToId = woo.buildSkuIndex();

        Map<Long, Integer> idQty = new LinkedHashMap<>();
        List<Map<String,Object>> preview = new ArrayList<>();
        List<String> notFoundSkus = new ArrayList<>();

        for (var e : skuQty.entrySet()) {
            Long id = skuToId.get(e.getKey());
            if (id != null) {
                idQty.put(id, e.getValue());
                preview.add(Map.of("id", id, "sku", e.getKey(), "qty", e.getValue()));
            } else {
                notFoundSkus.add(e.getKey());
            }
        }

        return Map.of(
                "supplier", supplier,
                "stockId", stockId,
                "willUpdate", preview.size(),
                "preview", preview.size() > 100 ? preview.subList(0, 100) : preview, // первые 100
                "notFoundSkus", notFoundSkus
        );
    }

    /** (Опционально) Инициализация SKU батчем */
    public Map<String, Object> initSkusFromDb() {
        Map<Long, String> idToSku = db.loadProductIdToSkuForInit();
        if (idToSku.isEmpty()) {
            return Map.of("updated", 0, "message", "idToSku is empty");
        }
        woo.batchUpdateSkus(idToSku);
        return Map.of("updated", idToSku.size());
    }
}