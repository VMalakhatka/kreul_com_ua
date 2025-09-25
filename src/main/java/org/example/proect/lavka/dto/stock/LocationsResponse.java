package org.example.proect.lavka.dto.stock;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LocationsResponse(
        int page,
        @JsonProperty("per_page") int perPage,
        int total,
        int totalPages,
        List<WooLocation> items
) {}