package org.example.proect.lavka.dto.stock;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// Маппинг: Woo term_id -> список MSSQL кодов
@JsonIgnoreProperties(ignoreUnknown = true)
public record LocMapEntry(
        @JsonProperty("term_id") long wooLocationId,
        @JsonProperty("slug") String slug,
        @JsonProperty("name") String name,
        @JsonProperty("codes")   List<String> msCodes
) {}