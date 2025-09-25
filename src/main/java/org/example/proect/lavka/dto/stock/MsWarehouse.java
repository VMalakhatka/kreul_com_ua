package org.example.proect.lavka.dto.stock;

public record MsWarehouse(
        String code,   // из ID_SCLAD
        String name,   // из NAME_SCLAD
        String descr   // из C_2 (может быть null)
) {}