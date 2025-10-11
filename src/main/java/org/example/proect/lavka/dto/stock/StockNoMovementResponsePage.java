package org.example.proect.lavka.dto.stock;

import java.time.Instant;
import java.util.List;

public record StockNoMovementResponsePage(
        List<NoMovementItem> items,
        int page,
        boolean last,
        Instant serverFrom,
        Instant serverTo
) {}