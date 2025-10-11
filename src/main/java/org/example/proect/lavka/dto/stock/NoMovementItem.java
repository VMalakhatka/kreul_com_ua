package org.example.proect.lavka.dto.stock;

public record NoMovementItem(
        String sku,
        String title,
        double totalQty
) {}