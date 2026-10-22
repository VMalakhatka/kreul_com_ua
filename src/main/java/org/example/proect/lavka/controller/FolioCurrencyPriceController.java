package org.example.proect.lavka.controller;

import org.example.proect.lavka.service.folio.FolioCurrencyPriceService;
import org.example.proect.lavka.dao.folio.FolioCurrencyPriceDao.Input;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/admin/folio/currency-ua")
public class FolioCurrencyPriceController {
    private final FolioCurrencyPriceService service;private final LavkaApiProperties properties;
    public FolioCurrencyPriceController(FolioCurrencyPriceService service,LavkaApiProperties properties){this.service=service;this.properties=properties;}
    public record Request(List<Input> rows,String token,boolean confirmed) {}
    @PostMapping("/{action}")
    public ResponseEntity<?> call(@PathVariable String action,@RequestBody Request request,
        @RequestHeader(value="X-Auth-Token",required=false) String token) {
        String expected=properties.getToken();
        if(expected==null||expected.isBlank())return response(503,Map.of("error","NOT_CONFIGURED"));
        if(token==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))return response(401,Map.of("error","UNAUTHORIZED"));
        if(request==null)return response(400,Map.of("error","INVALID_INPUT"));
        try {
            Object result=switch(action){case "preview"->service.preview(request.rows());case "apply"->service.apply(request.token(),request.confirmed());case "status"->service.status(request.token());default->throw new IllegalArgumentException("INVALID_ACTION");};
            return response(200,result);
        }catch(IllegalArgumentException e){return response(400,Map.of("error","INVALID_REQUEST_OR_TOKEN"));}
         catch(RuntimeException e){return response(503,Map.of("error","PREFLIGHT_OR_READ_FAILED"));}
    }
    private static ResponseEntity<?> response(int status,Object body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}
