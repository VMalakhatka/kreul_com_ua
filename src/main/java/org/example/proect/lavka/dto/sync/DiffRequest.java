package org.example.proect.lavka.dto.sync;

import java.util.List;

public record DiffRequest(
        String after,          // cursor по SKU (exclusive)
        Integer limit,         // 50..1000
        List<ItemHash> items   // то, что "видит" Woo в этой странице: [{sku, hash}]
) {
    public record ItemHash(String sku, String hash) {}
}
