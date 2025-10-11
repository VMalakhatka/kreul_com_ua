package org.example.proect.lavka.controller;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.ref.OpTypeDto;
import org.example.proect.lavka.service.RefService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "References")
@RequestMapping("/ref")
public class RefController {

    private final RefService refService;

    @GetMapping("/op-types")
    @Operation(summary = "Справочник типов операций (VID_OPER)",
            description = "Возвращает массив объектов {SIGNIFIC, PLANIR} из dbo.VID_OPER")
    public ResponseEntity<List<OpTypeDto>> opTypes() {
        return ResponseEntity.ok(refService.getOpTypes());
    }
}