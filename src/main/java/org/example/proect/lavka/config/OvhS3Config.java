package org.example.proect.lavka.config;

import org.example.proect.lavka.property.OvhS3Props;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(OvhS3Props.class)
public class OvhS3Config {

    @Bean
    public S3Client ovhS3(OvhS3Props props) {
        return S3Client.builder()
                .region(Region.of(props.region())) // "gra"
                .endpointOverride(URI.create(props.endpoint())) // https://s3.gra.io.cloud.ovh.net
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(props.accessKey(), props.secretKey())
                        )
                )
                .serviceConfiguration(
                        S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build()
                )
                .build();
    }
}