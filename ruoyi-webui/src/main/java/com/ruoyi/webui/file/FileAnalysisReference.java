package com.ruoyi.webui.file;

/** Metadata and a temporary model-readable URL, without loading file content. */
public final class FileAnalysisReference {
    public final String fileName;
    public final String mimeType;
    public final long size;
    public final String url;
    public final String validationUrl;
    public FileAnalysisReference(String fileName, String mimeType, long size, String url) {
        this(fileName, mimeType, size, url, url);
    }
    public FileAnalysisReference(String fileName, String mimeType, long size, String url, String validationUrl) {
        this.fileName = fileName; this.mimeType = mimeType; this.size = size; this.url = url;
        this.validationUrl = validationUrl;
    }
}
