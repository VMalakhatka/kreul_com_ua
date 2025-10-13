package org.example.proect.lavka.dao.stock;

import org.example.proect.lavka.dto.stock.PriceRow;

import java.util.List;
import java.util.Map;

public interface PriceDao {
    List<PriceRow> findLatestPrices(int stockId, List<String> skus, List<String> namePrices);
    // НОВОЕ: вернуть розничные цены (CENA_ARTIC) по складу + списку SKU
    Map<String, Double> findRetailPrices(int stockId, List<String> skus);
}