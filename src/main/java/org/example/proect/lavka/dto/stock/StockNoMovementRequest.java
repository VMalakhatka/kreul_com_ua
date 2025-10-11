package org.example.proect.lavka.dto.stock;

import java.util.List;

public record StockNoMovementRequest(
        List<StockQueryRequest.LocationCodes> locations, // те же LocationCodes (id, codes)
        List<String> opTypes,                            // SIGNIFIC из VID_OPER; можно null/пусто
        String from,                                     // ISO8601
        String to,                                       // ISO8601
        Integer page,
        Integer pageSize
) {}