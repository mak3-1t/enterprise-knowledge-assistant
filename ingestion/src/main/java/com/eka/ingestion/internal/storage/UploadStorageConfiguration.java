package com.eka.ingestion.internal.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "eka.upload.enabled", havingValue = "true")
public class UploadStorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client uploadS3Client(
            @Value("${eka.upload.endpoint}") URI endpoint,
            @Value("${eka.upload.region:us-east-1}") String region,
            @Value("${eka.upload.access-key}") String accessKey,
            @Value("${eka.upload.secret-key}") String secretKey) {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .httpClientBuilder(
                        UrlConnectionHttpClient.builder()
                                .connectionTimeout(Duration.ofSeconds(5))
                                .socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(
                        c ->
                                c.apiCallTimeout(Duration.ofSeconds(60))
                                        .apiCallAttemptTimeout(Duration.ofSeconds(30)))
                .build();
    }
}
