package org.example.proect.lavka.service;



import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.property.OvhS3Props;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OvhS3MediaService {
    private final S3Client s3;
    private final OvhS3Props props;

    public record Match(String key, String url, long size, Instant lastModified) {}

    public List<Match> findByFileName(String fileName) {
        String needle = fileName.trim().toLowerCase();
        int max = props.maxResults() != null ? props.maxResults() : 50;

        List<Match> out = new ArrayList<>();
        String prefix = props.prefix() != null ? props.prefix() : "";


            ListObjectsV2Request req = ListObjectsV2Request.builder()
                    .bucket(props.bucket())
                    .prefix(prefix)   // ищем под uploads/
                    .maxKeys(1000)
                    .build();

            for (ListObjectsV2Response page : s3.listObjectsV2Paginator(req)) {
                for (S3Object o : page.contents()) {
                    String key = o.key();
                    String name = key.substring(key.lastIndexOf('/') + 1);
                    if (name.equalsIgnoreCase(needle)) {
                        out.add(new Match(key, publicUrlVirtualHosted(key), o.size(), o.lastModified()));
                        if (out.size() >= max) return out;
                    }
                }
            }
        return out;
    }

    /** Публичный URL в virtual-hosted стиле (как на сайте). */
    private String publicUrlVirtualHosted(String key) {
        // мини-энкодинг: оставляем слэши, пробелы -> %20
        String safe = key.replace(" ", "%20");
        return "https://" + props.bucket() + ".s3.gra.io.cloud.ovh.net/" + safe;
    }
}