// StockMovementRequest.java
package org.example.proect.lavka.dto.stock;

import java.util.List;

public record StockMovementRequest(
        List<StockQueryRequest.LocationCodes> locations,
        String from,            // ISO-8601, обязательно
        Integer page,
        Integer pageSize
) {}