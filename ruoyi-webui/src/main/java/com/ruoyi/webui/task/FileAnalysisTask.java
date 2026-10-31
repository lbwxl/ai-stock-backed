package com.ruoyi.webui.task;

import com.ruoyi.common.core.domain.BaseEntity;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.Date;
import java.util.List;
import java.util.Collections;

/** Persistent file-analysis record. Does not contain model or storage credentials. */
public class FileAnalysisTask extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Boolean fileAvailable;
    private String fileErrorMessage;
    public Boolean getFileAvailable() { return fileAvailable; }
    public void setFileAvailable(Boolean value) { fileAvailable = value; }
    public String getFileErrorMessage() { return fileErrorMessage; }
    public void setFileErrorMessage(String value) { fileErrorMessage = value; }
    private Long id;
    private Long userId;
    private String userName;
    private String fileId;
    private String fileName;
    private String mimeType;
    private Long fileSize;
    private String fileUrl;
    private String processingRequirement;
    private String analysisRequirement;
    private String analysisText;
    private List<FileAnalysisResultFile> resultFiles = Collections.emptyList();
    public List<FileAnalysisResultFile> getResultFiles() { return resultFiles; }
    public void setResultFiles(List<FileAnalysisResultFile> value) { resultFiles = value; }
    private String status;
    private String processingMode;
    private String modelName;
    private String errorCode;
    private String errorMessage;
    private String requestId;
    private Integer version;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date completedAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getUserName() { return userName; }
    public void setUserName(String value) { userName = value; }
    public String getFileId() { return fileId; }
    public void setFileId(String value) { fileId = value; }
    public String getFileName() { return fileName; }
    public void setFileName(String value) { fileName = value; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String value) { mimeType = value; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long value) { fileSize = value; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String value) { fileUrl = value; }
    public String getProcessingRequirement() { return processingRequirement; }
    public void setProcessingRequirement(String value) { processingRequirement = value; }
    public String getAnalysisRequirement() { return analysisRequirement; }
    public void setAnalysisRequirement(String value) { analysisRequirement = value; }
    public String getAnalysisText() { return analysisText; }
    public void setAnalysisText(String value) { analysisText = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getProcessingMode() { return processingMode; }
    public void setProcessingMode(String value) { processingMode = value; }
    public String getModelName() { return modelName; }
    public void setModelName(String value) { modelName = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date value) { startedAt = value; }
    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date value) { completedAt = value; }
}
