package org.example.proect.lavka.dto;

public record SeenItem(
        String sku,
        String hash,
        Long postId // может быть null если что-то странное, но обычно не null
) {}