package org.example.proect.lavka.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CardTovForSync(
        String sku,
        String name,
        String img,
        String edinIzmer,
        String globalUniqueId,
        Double weight,
        Double length,
        Double width,
        Double height,
        Integer status,
        Double vesEdinic,
        String description,
        String razmIzmer,
        String grDescr,
        Long groupId,
        String hash
) {}