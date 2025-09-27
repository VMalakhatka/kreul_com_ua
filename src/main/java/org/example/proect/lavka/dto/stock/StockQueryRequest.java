package org.example.proect.lavka.dto.stock;

import java.util.List;

public record StockQueryRequest(
        List<String> skus,                  // список SKU
        List<LocationCodes> locations       // список Woo-складов с их MS-кодами
) {
    public record LocationCodes(
            long id,                        // term_id склада (Woo)
            List<Integer> codes             // список ID_SCLAD из MSSQL, которые мэппятся на этот Woo-склад
    ) {}
}