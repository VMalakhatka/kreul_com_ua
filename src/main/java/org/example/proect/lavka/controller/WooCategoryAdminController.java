package org.example.proect.lavka.controller;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.service.category.WooCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WooCategoryAdminController {

    private final WooCategoryService service;

    /** Пример: GET /admin/woo/cat/ensure?l1=Скульптура&l2=Пластичні маси&l3=Cernit&l4=56гр&l5=Number One */
    @GetMapping("/admin/woo/cat/ensure")
    public long ensure(
            @RequestParam(required = false) String l1,
            @RequestParam(required = false) String l2,
            @RequestParam(required = false) String l3,
            @RequestParam(required = false) String l4,
            @RequestParam(required = false) String l5,
            @RequestParam(required = false) String l6
    ) {
        return service.ensureCategoryPath(l1,l2,l3,l4,l5,l6);
    }
}