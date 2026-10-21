package com.ruoyi.webui.domain.vo;

public class AiServiceVo {
    private Long aiServiceId;
    private String serviceName;
    private String aiServiceKey;
    private String serviceUrl;
    private String modelName;

    public Long getAiServiceId() {
        return this.aiServiceId;
    }

    public void setAiServiceId(Long aiServiceId) {
        this.aiServiceId = aiServiceId;
    }

    public String getServiceName() {
        return this.serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getAiServiceKey() {
        return this.aiServiceKey;
    }

    public void setAiServiceKey(String aiServiceKey) {
        this.aiServiceKey = aiServiceKey;
    }

    public String getServiceUrl() {
        return this.serviceUrl;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public String getModelName() {
        return this.modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }
}
