package org.example.proect.lavka.dto.stock;

import java.util.List;

// Маппинг: Woo term_id -> список MSSQL кодов
public record LocMapEntry(long wooLocationId, List<String> msCodes) {}
