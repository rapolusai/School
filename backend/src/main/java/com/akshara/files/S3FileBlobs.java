package com.akshara.files;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Uploaded files' bytes in a private S3 bucket ({@code akshara.files.store=s3}, bucket {@code akshara.files.s3.bucket}).
 * Credentials come from the default chain, which on ECS is the API task's role (storage.tf: read, write and delete
 * objects in this bucket only). The bucket encrypts with its own KMS key and keeps old versions for a while.
 */
@Component
@ConditionalOnProperty(name = "akshara.files.store", havingValue = "s3")
class S3FileBlobs implements FileBlobs, DisposableBean {

    private final S3Client s3;
    private final String bucket;

    @Autowired
    S3FileBlobs(@Value("${akshara.files.s3.bucket:}") String bucket,
            @Value("${akshara.files.s3.region:}") String region) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("akshara.files.store is s3 but FILES_S3_BUCKET is not set");
        }
        this.bucket = bucket;
        var builder = S3Client.builder();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }
        this.s3 = builder.build();
    }

    /** For tests: a client pointed at a local S3 (for example LocalStack or MinIO). */
    S3FileBlobs(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                .contentLength((long) bytes.length).build(), RequestBody.fromBytes(bytes));
    }

    @Override
    public byte[] get(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void destroy() {
        s3.close();
    }
}
