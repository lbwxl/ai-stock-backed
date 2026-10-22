package com.ruoyi.webui.file;

public final class FileAnalysisResult {
    public final String requestId;
    public final String fileName;
    public final String analysisText;
    // Reserved for a future processor-generated artifact. No edited file is fabricated.
    public final Object previewFile = null;

    public FileAnalysisResult(String requestId, String fileName, String analysisText) {
        this.requestId = requestId;
        this.fileName = fileName;
        this.analysisText = analysisText;
    }
}
