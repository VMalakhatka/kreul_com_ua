package org.example.proect.lavka.dto.stock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Woo-склад (терм таксономии product_cat, или кастомный “location” — не важно для клиента)
@JsonIgnoreProperties(ignoreUnknown = true)
public record WooLocation(
        long id,
        String name,
        String slug,
        Long parent,     // из ответа WP
        Integer count    // из ответа WP
) {}