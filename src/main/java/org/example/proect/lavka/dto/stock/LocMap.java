package org.example.proect.lavka.dto.stock;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LocMap(
        @JsonProperty("items") List<LocMapEntry> entries
) {}