package org.example.proect.lavka.dto.stock;

import java.util.List;

// ответ Java
public record StockQueryResponse(List<SkuStock> items) {
    public record SkuStock(String sku, List<LocQty> lines, int total) {}
    public record LocQty(long id, int qty) {}
}