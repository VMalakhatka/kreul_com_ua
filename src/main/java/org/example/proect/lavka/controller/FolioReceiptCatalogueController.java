package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.folio.FolioReceiptCatalogueDao;
import org.example.proect.lavka.dao.stock.MsWarehouseDao;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;

/** Manager-facing, read-only source for receipt/invoice product mailings. */
@RestController
@RequestMapping("/admin/folio/receipt-catalogue")
public class FolioReceiptCatalogueController {
    private static final List<String> DOCUMENT_TYPES=List.of("receipt","invoice");
    private final FolioReceiptCatalogueDao dao;
    private final MsWarehouseDao warehouses;
    public FolioReceiptCatalogueController(FolioReceiptCatalogueDao dao, MsWarehouseDao warehouses) {
        this.dao=dao; this.warehouses=warehouses;
    }
    @GetMapping("/warehouses")
    public Map<String,Object> warehouses() { return Map.of("ok",true,"warehouses",warehouses.findAllVisible()); }
    @GetMapping
    public Map<String,Object> documents(@RequestParam(name="warehouseId") int warehouseId,
            @RequestParam(name="date") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name="afterId",defaultValue="0") long afterId,
            @RequestParam(name="documentType",defaultValue="receipt") String documentType) {
        validate(warehouseId,date);
        validateType(documentType);
        if(afterId<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid cursor");
        var rows=dao.documents(warehouseId,date,afterId,documentType);
        boolean more=rows.size()>100;
        var page=more?rows.subList(0,100):rows;
        return Map.of("ok",true,"warehouseId",warehouseId,"date",date.toString(),"documents",page,
                "hasMore",more,"nextAfterId",more?page.get(page.size()-1).id():0L,
                "documentType",documentType,"documentTypes",DOCUMENT_TYPES);
    }
    @GetMapping("/{id}/skus")
    public Map<String,Object> skus(@PathVariable(name="id") long id,@RequestParam(name="warehouseId") int warehouseId,
            @RequestParam(name="date") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name="documentType",defaultValue="receipt") String documentType) {
        validate(warehouseId,date);
        validateType(documentType);
        if(id<=0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid document");
        var rows=dao.skus(id,warehouseId,date,documentType);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No eligible products in the selected receipt or invoice");
        if(rows.size()>2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Document exceeds 2000 products");
        return Map.of("ok",true,"documentId",id,"warehouseId",warehouseId,"date",date.toString(),"skus",rows,"documentType",documentType,"documentTypes",DOCUMENT_TYPES);
    }
    private static void validateType(String type) {
        if(!"all".equals(type) && !DOCUMENT_TYPES.contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid document type");
    }
    private static void validate(int warehouse, LocalDate date) {
        if(warehouse<=0 || date==null || date.isBefore(LocalDate.of(1900,1,1)) || date.isAfter(LocalDate.of(2099,12,31)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid warehouse/date");
    }
}
