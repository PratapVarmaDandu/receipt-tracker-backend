package com.receipttracker.service;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;

/**
 * Not Spring-managed — instantiated per-user with their own credentials.
 * Always call {@link #close()} when done to release the S3 client resources.
 */
public class S3FileStorageService implements AutoCloseable {

    private final S3Client client;
    private final String bucket;

    public S3FileStorageService(String bucket, String region, String accessKeyId, String secretAccessKey) {
        this.bucket = bucket;
        this.client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build();
    }

    /** Uploads a local file to S3 under the given key. */
    public void store(File localFile, String key) {
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromFile(localFile));
    }

    /** Verifies that the bucket exists and credentials have at least read access. */
    public void testConnection() {
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    }

    /** Deletes an object from the bucket. No-op if the key does not exist. */
    public void deleteObject(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /** Returns the size in bytes of an object, or 0 if the key is not found. */
    public long getObjectSize(String key) {
        HeadObjectResponse response = client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).build());
        return response.contentLength() != null ? response.contentLength() : 0L;
    }

    @Override
    public void close() {
        client.close();
    }
}
