package org.example.proect.lavka.property;


import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "ovh.s3")
public record OvhS3Props(
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        String prefix,
        Integer maxResults
) {}