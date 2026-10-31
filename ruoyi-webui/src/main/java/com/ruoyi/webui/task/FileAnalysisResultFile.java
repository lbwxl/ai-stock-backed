package com.ruoyi.webui.task;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Public attachment metadata. Storage ownership never comes from the browser. */
public class FileAnalysisResultFile {
    private String id;
    private Long taskId;
    private Long storageUserId;
    private String fileName;
    private String mimeType;
    private Long fileSize;
    private boolean published;
    private String createBy;
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    @JsonIgnore public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { taskId = value; }
    @JsonIgnore public Long getStorageUserId() { return storageUserId; }
    public void setStorageUserId(Long value) { storageUserId = value; }
    public String getFileName() { return fileName; }
    public void setFileName(String value) { fileName = value; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String value) { mimeType = value; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long value) { fileSize = value; }
    @JsonIgnore public boolean isPublished() { return published; }
    public void setPublished(boolean value) { published = value; }
    @JsonIgnore public String getCreateBy() { return createBy; }
    public void setCreateBy(String value) { createBy = value; }
}
