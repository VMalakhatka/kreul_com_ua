package org.example.proect.lavka.dto.category;

import lombok.Data;

@Data
public class WooCategory {
    private Long id;
    private String name;
    private String slug;
    private Long parent;
}