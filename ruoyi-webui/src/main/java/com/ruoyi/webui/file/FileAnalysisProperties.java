package com.ruoyi.webui.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "webui.file-analysis")
public class FileAnalysisProperties {
    private String storageType = "local";
    private Minio minio = new Minio();
    private String storagePath = java.nio.file.Paths.get(System.getProperty("user.dir"), ".local", "ai-files").toString();
    private String publicBaseUrl = "";
    private String publicBaseUrlFile = java.nio.file.Paths.get(System.getProperty("user.dir"), ".local", "file-tunnel-url").toString();
    private long modelUrlTtlSeconds = 1800;
    private long retentionSeconds = 86400;
    private long maxFileSize = 10 * 1024 * 1024;
    private int maxToolRounds = 3;
    private String toolHandler = "unconfigured";

    public String getStorageType() { return storageType; }
    public void setStorageType(String value) { storageType = value; }
    public Minio getMinio() { return minio; }
    public void setMinio(Minio value) { minio = value; }

    public static class Minio {
        private String configFile = "";
        private String endpoint = "";
        private String publicEndpoint = "";
        private String accessKey = "";
        private String secretKey = "";
        private String bucket = "";
        private String region = "us-east-1";
        private String prefix = "file-analysis/";
        public String getConfigFile() { return configFile; }
        public void setConfigFile(String value) { configFile = value; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String value) { endpoint = value; }
        public String getPublicEndpoint() { return publicEndpoint; }
        public void setPublicEndpoint(String value) { publicEndpoint = value; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String value) { accessKey = value; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String value) { secretKey = value; }
        public String getBucket() { return bucket; }
        public void setBucket(String value) { bucket = value; }
        public String getRegion() { return region; }
        public void setRegion(String value) { region = value; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String value) { prefix = value; }
    }

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
