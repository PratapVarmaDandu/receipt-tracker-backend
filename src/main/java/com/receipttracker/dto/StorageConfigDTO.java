package com.receipttracker.dto;

import com.receipttracker.model.StorageType;
import lombok.Data;

@Data
public class StorageConfigDTO {
    private StorageType storageType;
    private String localStoragePath;
    /** Resolved absolute path of the server's default upload directory (upload.dir). */
    private String defaultStoragePath;
    private String s3BucketName;
    private String s3Region;
    private String s3AccessKeyId;
    /** Write-only: never returned on GET responses. */
    private String s3SecretAccessKey;
    /** True when a secret access key is already saved — lets the frontend show "Configured" badge. */
    private boolean s3SecretKeyConfigured;
    private boolean storageConfigured;
}
