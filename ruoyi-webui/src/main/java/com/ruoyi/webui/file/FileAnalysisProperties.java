package com.ruoyi.webui.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "webui.file-analysis")
public class FileAnalysisProperties {
    private String storagePath = java.nio.file.Paths.get(System.getProperty("user.dir"), ".local", "ai-files").toString();
    private String publicBaseUrl = "";
    private String publicBaseUrlFile = java.nio.file.Paths.get(System.getProperty("user.dir"), ".local", "file-tunnel-url").toString();
    private long modelUrlTtlSeconds = 1800;
    private long retentionSeconds = 86400;
    private long maxFileSize = 10 * 1024 * 1024;
    private int maxToolRounds = 3;
    private String toolHandler = "unconfigured";

    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String value) { storagePath = value; }
    public long getRetentionSeconds() { return retentionSeconds; }
    public void setRetentionSeconds(long value) { retentionSeconds = value; }

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String value) { publicBaseUrl = value; }
    public String getPublicBaseUrlFile() { return publicBaseUrlFile; }
    public void setPublicBaseUrlFile(String value) { publicBaseUrlFile = value; }
    public long getModelUrlTtlSeconds() { return modelUrlTtlSeconds; }
    public void setModelUrlTtlSeconds(long value) { modelUrlTtlSeconds = value; }

    public long getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(long value) { maxFileSize = value; }
    public int getMaxToolRounds() { return maxToolRounds; }
    public void setMaxToolRounds(int value) { maxToolRounds = value; }
    public String getToolHandler() { return toolHandler; }
    public void setToolHandler(String value) { toolHandler = value; }
}
