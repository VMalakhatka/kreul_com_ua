package org.example.proect.lavka.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO под представление dbo.card_tov
 */
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardTovExportDto {
    private String  sku;
    private String  name;
    private String  NGROUP_TVR;
    private String  NGROUP_TV2;
    private String  NGROUP_TV3;
    private String  NGROUP_TV4;
    private String  NGROUP_TV5;
    private String  NGROUP_TV6;
    private String  img;
    private String  EDIN_IZMER;
    private String  global_unique_id;
    private Double  weight;
    private Double  length;
    private Double  width;
    private Double  height;
    private Integer status;
    private Double  VES_EDINIC;
    private String  DESCRIPTION;
    private String  RAZM_IZMER;
    private String  gr_descr;
}