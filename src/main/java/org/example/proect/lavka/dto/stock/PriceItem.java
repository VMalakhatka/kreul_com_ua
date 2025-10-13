package org.example.proect.lavka.dto.stock;

import java.util.Map;

// ответ: для каждого SKU — price (розница) + мапа "id_в_Woo" -> значение цены
public record PriceItem(
        String sku,
        Double price,              // СCL_ARTC.CENA_ARTIC
        Map<String, Double> prices // "wooId" -> numeric price
) {}