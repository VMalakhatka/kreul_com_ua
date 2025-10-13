package org.example.proect.lavka.dto.stock;

import java.util.List;

public record PriceQueryResponse(
        List<PriceItem> items
) {}