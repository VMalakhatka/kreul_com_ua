package org.example.proect.lavka.dto.ref;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OpTypeDto(
        @JsonProperty("SIGNIFIC") String signific,
        @JsonProperty("PLANIR")   Double planir   // null | 0 | 1
) {}