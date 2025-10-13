package org.example.proect.lavka.dto.stock;

import java.util.List;
import java.util.Map;

// запрос: список SKU + мапа "id_в_Woo" -> NAME_PRICE в MSSQL
public record PriceQueryRequest(
        List<String> skus,
        Map<String, String> priceMap // пример: {"opt":"ОПТ","partner":"ПАРТНЁР"}
) {}