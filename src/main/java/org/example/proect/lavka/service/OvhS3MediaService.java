package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.property.OvhS3Props;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OvhS3MediaService {

    private final S3Client s3;
    private final OvhS3Props props;

    /** Результат «лучшего» совпадения по имени файла: fullKey + публичный URL. */
    public record Res(String fullKey, String publicUrl) {}

    /** Совпадение из полного скана: ключ, url, размер и время модификации. */
    public record Match(String key, String url, long size, Instant lastModified) {}

    /** По имени файла находит все совпадения и возвращает список (не больше maxResults). */
    public List<Match> findByFileName(String fileName) {
        String needle = fileName.trim().toLowerCase();
        int max = props.maxResults() != null ? props.maxResults() : 50;

        List<Match> out = new ArrayList<>();
        String prefix = props.prefix() != null ? props.prefix() : "";

        ListObjectsV2Request req = ListObjectsV2Request.builder()
                .bucket(props.bucket())
                .prefix(prefix)   // например "wp-content/uploads/"
                .maxKeys(1000)
                .build();

        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(req)) {
            for (S3Object o : page.contents()) {
                String key = o.key();
                String name = key.substring(key.lastIndexOf('/') + 1);
                if (name.equalsIgnoreCase(needle)) {
                    out.add(new Match(key, toPublicUrl(key), o.size(), o.lastModified()));
                    if (out.size() >= max) return out;
                }
            }
        }
        return out;
    }

    /** Возвращает лучший ключ (по lastModified desc, затем size desc) и его публичный URL. */
    public Res resolve(String fileName) {
        List<Match> matches = findByFileName(fileName);
        if (matches.isEmpty()) return null;

        Match best = matches.stream()
                .sorted(Comparator
                        .comparing((Match m) -> m.lastModified() == null ? Instant.EPOCH : m.lastModified())
                        .reversed()
                        .thenComparingLong(Match::size).reversed())
                .findFirst().orElse(matches.get(0));

        return new Res(best.key(), best.url());
    }

    /** Публичный URL в virtual-hosted стиле (как на сайте / как у тебя в примерах). */
    public String toPublicUrl(String key) {
        String safe = key.replace(" ", "%20");
        return "https://" + props.bucket() + ".s3.gra.io.cloud.ovh.net/" + safe;
    }
}