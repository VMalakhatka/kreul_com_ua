package org.example.proect.lavka.dto.stock;

// внутренняя строка из БД
public record PriceRow(
        String sku,
        String namePrice,
        Double rubPrice,
        Double valtPrice
) {}