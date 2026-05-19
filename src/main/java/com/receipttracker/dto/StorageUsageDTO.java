package com.receipttracker.dto;

import lombok.Data;

@Data
public class StorageUsageDTO {
    /** Raw byte count of all receipt files in the user's configured storage. */
    private long totalBytes;
    /** Human-readable size (e.g. "12.4 MB"). */
    private String formattedSize;
    /** Number of receipts that have an associated file in storage. */
    private int filesFound;
    /** Receipts that have a filename recorded in DB but whose file could not be located. */
    private int filesNotFound;
    /** "LOCAL" or "S3". */
    private String storageType;
    /** Filesystem path (LOCAL) or "s3://bucket (region)" (S3). */
    private String location;
}
