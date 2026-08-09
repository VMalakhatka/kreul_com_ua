package org.example.proect.lavka.controller;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaChangeRequest;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaChangeResponse;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaSearchResponse;
import org.example.proect.lavka.service.folio.FolioProductMediaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/folio/product-media")
public class FolioProductMediaController {

    private final FolioProductMediaService service;

    @GetMapping
    public ResponseEntity<FolioProductMediaSearchResponse> search(
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String match,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        FolioProductMediaSearchResponse response = service.search(
                sku, filename, role, match, limit, offset);
        return response.ok()
                ? ResponseEntity.ok(response)
                : ResponseEntity.badRequest().body(response);
    }

    @PostMapping("/changes")
    public ResponseEntity<FolioProductMediaChangeResponse> change(
            @RequestBody FolioProductMediaChangeRequest request
    ) {
        FolioProductMediaChangeResponse response = service.change(request);
        return ResponseEntity.ok(response);
    }
}
