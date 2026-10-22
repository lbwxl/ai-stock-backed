package com.ruoyi.webui.file;

/** One authenticated upload. No extraction, OCR, conversion, or embedded file bytes. */
public final class FileAnalysisContext {
    private final Long userId;
    private final String requestId;
    private final String fileName;
    private final String mimeType;
    private final String fileUrl;
    private final long fileSize;
    private final String processingRequirement;
    private final String analysisRequirement;

    public FileAnalysisContext(Long userId, String requestId, String fileName, String mimeType, String fileUrl, long fileSize,
            String processingRequirement, String analysisRequirement) {
        this.userId = userId;
        this.requestId = requestId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.fileUrl = fileUrl;
        this.fileSize = fileSize;
        this.processingRequirement = processingRequirement;
        this.analysisRequirement = analysisRequirement;
    }

    public Long getUserId() { return userId; }
    public String getRequestId() { return requestId; }
    public String getFileName() { return fileName; }
    public String getMimeType() { return mimeType; }
    public String getFileUrl() { return fileUrl; }
    public long getFileSize() { return fileSize; }
    public String getProcessingRequirement() { return processingRequirement; }
    public String getAnalysisRequirement() { return analysisRequirement; }
}
