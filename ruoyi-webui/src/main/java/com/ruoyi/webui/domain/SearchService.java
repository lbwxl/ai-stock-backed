package com.ruoyi.webui.domain;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public class SearchService extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long id;
    @Excel(name="服务名称")
    private String serviceName;
    @Excel(name="服务URL")
    private String serviceUrl;
    @Excel(name="搜索引擎标识符(cx值)")
    private String searchEngineCx;

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return this.id;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return this.serviceName;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public String getServiceUrl() {
        return this.serviceUrl;
    }

    public void setSearchEngineCx(String searchEngineCx) {
        this.searchEngineCx = searchEngineCx;
    }

    public String getSearchEngineCx() {
        return this.searchEngineCx;
    }

    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE).append("id", this.getId()).append("serviceName", this.getServiceName()).append("serviceUrl", this.getServiceUrl()).append("searchEngineCx", this.getSearchEngineCx()).toString();
    }
}
