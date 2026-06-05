package com.receipttracker.dto;

import com.receipttracker.model.DocumentCategory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class DocumentDTO {
    private Long id;
    private String title;
    private String originalFileName;
    private String contentType;
    private Long fileSize;
    private DocumentCategory category;
    private String subcategory;
    private Integer documentYear;
    private LocalDate expiryDate;
    private String notes;
    private boolean archived;
    /** Computed: ACTIVE | EXPIRING_SOON | EXPIRED */
    private String status;
    /** Days until expiry — negative if already expired, null if no expiry. */
    private Integer daysUntilExpiry;
    private List<DocumentNextStepDTO> nextSteps;
    private LocalDateTime uploadedAt;
    private LocalDateTime updatedAt;

    public DocumentDTO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public DocumentCategory getCategory() { return category; }
    public void setCategory(DocumentCategory category) { this.category = category; }
    public String getSubcategory() { return subcategory; }
    public void setSubcategory(String subcategory) { this.subcategory = subcategory; }
    public Integer getDocumentYear() { return documentYear; }
    public void setDocumentYear(Integer documentYear) { this.documentYear = documentYear; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getDaysUntilExpiry() { return daysUntilExpiry; }
    public void setDaysUntilExpiry(Integer daysUntilExpiry) { this.daysUntilExpiry = daysUntilExpiry; }
    public List<DocumentNextStepDTO> getNextSteps() { return nextSteps; }
    public void setNextSteps(List<DocumentNextStepDTO> nextSteps) { this.nextSteps = nextSteps; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
