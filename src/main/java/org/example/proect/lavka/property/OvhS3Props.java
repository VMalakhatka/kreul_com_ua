package org.example.proect.lavka.property;


import com.sun.jna.platform.unix.solaris.LibKstat;
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
        String scheme,
        String publicBaseUrl,
        Integer maxResults
) {}