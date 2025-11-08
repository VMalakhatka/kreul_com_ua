package org.example.proect.lavka.controller;


import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.service.MsToWpImageSyncService;
import org.example.proect.lavka.service.OvhS3MediaService;
import org.example.proect.lavka.service.S3MediaIndexService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/media")
public class MediaController {

    private final OvhS3MediaService media;
    private final S3MediaIndexService index;
    private final MsToWpImageSyncService mediaSync;
    public enum Mode { featured, gallery, both }

    public record SyncRequest(
            List<String> skus,
            Mode mode,                   // featured | gallery | both
            Boolean touchOnUpdate,
            Integer galleryStartPos,
            Integer limitPerSku,
            Boolean dry
    ) {}

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@RequestBody SyncRequest req) {
        // 1) Быстрая валидация
        if (req.skus() == null || req.skus().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "skus required"));
        }
        if (req.skus().size() > 3000) { // подбери под себя
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "too many skus"));
        }

        // 2) Значения по умолчанию
        String mode = (req.mode() == null ? Mode.both : req.mode()).name();
        boolean touch = Boolean.TRUE.equals(req.touchOnUpdate());
        int galPos = req.galleryStartPos() == null ? 0 : Math.max(0, req.galleryStartPos());
        int limit = req.limitPerSku() == null ? 10 : Math.max(1, req.limitPerSku());
        boolean dry = Boolean.TRUE.equals(req.dry());

        // 3) Вызов сервиса
        Map<String, Object> result = mediaSync.syncFromMs(
                req.skus(), mode, galPos, limit, dry
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/reindex")
    public ResponseEntity<?> reindex() {
        var res = index.reindexAll();
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "updatedRows", res.insertedOrUpdated(),
                "pages", res.pages()
        ));
    }

    @GetMapping("/index/find")
    public ResponseEntity<?> find(@RequestParam("name") String name) {
        var rows = index.find(name);
        return ResponseEntity.ok(Map.of(
                "count", rows.size(),
                "items", rows.stream().map(r -> Map.of(
                        "filename", r.filenameLower(),
                        "key", r.fullKey(),
                        "url", index.toPublicUrl(r.fullKey()),
                        "size", r.sizeBytes(),
                        "lastModified", r.lastModified(),
                        "etag", r.etag()
                )).collect(Collectors.toList())
        ));
    }

    /**
     * GET /admin/media/search?name=cr059.jpg
     */
    @GetMapping("/search")
    public ResponseEntity<List<OvhS3MediaService.Match>> search(@RequestParam("name") String name) {
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(media.findByFileName(name));
    }
}