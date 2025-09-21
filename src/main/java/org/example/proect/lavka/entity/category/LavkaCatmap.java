package org.example.proect.lavka.entity.category;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class LavkaCatmap {
    private Long id;

    private String pathText;
    private String pathHash;

    private String l1;
    private String l2;
    private String l3;
    private String l4;
    private String l5;
    private String l6;
    private int depth;

    private String parentPathHash;
    private Long wcParentId;

    private Long wcTermId;
    private String slug;

    private String l1Norm;
    private String l2Norm;
    private String l3Norm;
    private String l4Norm;
    private String l5Norm;
    private String l6Norm;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}