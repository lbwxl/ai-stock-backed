package com.ruoyi.webui.domain.vo;

public class SearchServiceVo {
    private Long searchServiceId;
    private String searchServiceName;
    private String searchServiceUrl;
    private String searchServiceKey;
    private String searchEngineCx;

    public Long getSearchServiceId() {
        return this.searchServiceId;
    }

    public void setSearchServiceId(Long searchServiceId) {
        this.searchServiceId = searchServiceId;
    }

    public String getSearchServiceName() {
        return this.searchServiceName;
    }

    public void setSearchServiceName(String searchServiceName) {
        this.searchServiceName = searchServiceName;
    }

    public String getSearchServiceUrl() {
        return this.searchServiceUrl;
    }

    public void setSearchServiceUrl(String searchServiceUrl) {
        this.searchServiceUrl = searchServiceUrl;
    }

    public String getSearchServiceKey() {
        return this.searchServiceKey;
    }

    public void setSearchServiceKey(String searchServiceKey) {
        this.searchServiceKey = searchServiceKey;
    }

    public String getSearchEngineCx() {
        return this.searchEngineCx;
    }

    public void setSearchEngineCx(String searchEngineCx) {
        this.searchEngineCx = searchEngineCx;
    }
}
