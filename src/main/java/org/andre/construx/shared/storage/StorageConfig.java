package org.andre.construx.shared.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Credentials come from the AWS default chain: AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY, AWS_PROFILE,
 * or an instance role. Nothing is resolved until the first call, so the context starts without them.
 */
@Configuration
public class StorageConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${aws.region}") String region) {
        return S3Client.builder().region(Region.of(region)).credentialsProvider(DefaultCredentialsProvider.create()).build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(@Value("${aws.region}") String region) {
        return S3Presigner.builder().region(Region.of(region)).credentialsProvider(DefaultCredentialsProvider.create()).build();
    }

    @Bean
    ObjectStorage objectStorage(S3Client client, S3Presigner presigner, @Value("${aws.bucket.name}") String bucket) {
        return new S3ObjectStorage(client, presigner, bucket);
    }
}
