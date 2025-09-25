package org.example.proect.lavka.dto.stock;

import java.util.List;

// Woo-склад (терм таксономии product_cat, или кастомный “location” — не важно для клиента)
public record WooLocation(
        long id,            // term_id
        String name,        // “Киев Олимпийский”
        String slug,        // “kiev1”
        String extCodes     // CSV из term meta: "D01,D02" (может быть пустым)
) {}