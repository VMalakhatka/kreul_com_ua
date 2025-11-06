package org.example.proect.lavka.controller;


import lombok.RequiredArgsConstructor;
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