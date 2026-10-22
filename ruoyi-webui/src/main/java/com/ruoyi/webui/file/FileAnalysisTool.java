package com.ruoyi.webui.file;

/**
 * Extension point for third-party platforms or another model.
 * Implement as a Spring bean and select its ID via webui.file-analysis.tool-handler.
 * Called ONLY when the current user's model invokes analyze_uploaded_file.
 */
public interface FileAnalysisTool {
    String getId();

    /** Receives a temporary URL for the original file and the model's task; returns tool data to that same user model. */
    String analyze(FileAnalysisContext context, String task);
}
