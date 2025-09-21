package org.example.proect.lavka.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.example.proect.lavka.entity.SclArtc;
import org.example.proect.lavka.entity.SclMove;
import org.example.proect.lavka.rabbitMQ.publisher.GoodsClient;
import org.example.proect.lavka.service.GoodsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Test get goods from Paint",description = "enter supplier and stock id")
@Validated
@RequestMapping(path = "/goods")
@RestController
public class GoodsContorller {

    GoodsClient goodsClient;
    GoodsService goodsService;

    public GoodsContorller(GoodsClient goodsClient, GoodsService goodsService) {
        this.goodsClient = goodsClient;
        this.goodsService = goodsService;
    }

    @GetMapping("{supp}/by_stock/{id}")
    @Operation(
            summary = "Get all products by supplier and stock",
            description = "Outputs a list of all found products of the supplier and the warehouse, specified in path"
    )
    public List<SclArtc> getGoodsBySupplierAndStockId(@PathVariable("supp") @Parameter(description = "supplier")
                                             @Size(min = 1,max = 8) String supp,
                                                      @PathVariable("id") @Min(0)
                                            @Parameter(description = "the stock ID") long id){
        return goodsService.getGoodsBySupplierAndStockId(supp,id);
    }

    @PostMapping("/move/{nameArtc}/by_stock/{id}")
    @Operation(
            summary = " ",
            description = " "
    )
    public List<SclMove> getMoveByNameGoodsAndNumberStock(@PathVariable("nameArtc") @Parameter(description = "unique product identifier")
                                                          @Size(min = 1,max = 20) String nameArtc,
                                                          @PathVariable("id") int id,
                                                          @RequestBody
                                                          DateRangeForm startAndEnd){
        return goodsService.getMoveByNameGoodsAndStockId(nameArtc,id,startAndEnd.getStart(),startAndEnd.getEnd());
    }

    @GetMapping("/{num_doc}")
    @Operation(
            summary = "Get all products by doc ",
            description = "Outputs a list of all found products of unic num doc"
    )
    public List<SclArtc> getGoodsByNumDoc(@PathVariable("num_doc") @Parameter(description = "unic numer of document where find list jf goods for forecast")
                                                      @Min(0) long numDoc){
        return goodsService.getGoodsByNumDoc(numDoc);
    }

}
