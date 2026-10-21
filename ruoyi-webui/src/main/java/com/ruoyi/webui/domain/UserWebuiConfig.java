package com.ruoyi.webui.domain;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public class UserWebuiConfig extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long id;
    @Excel(name="用户ID")
    private Long userId;
    @Excel(name="AI服务")
    private Long aiServiceId;
    @Excel(name="AI服务密钥")
    private String aiServiceKey;
    @Excel(name="模型")
    private Long modelId;
    @Excel(name="搜索服务")
    private Long searchServiceId;
    @Excel(name="搜索服务密钥", readConverterExp="如=API,K=ey")
    private String searchServiceKey;

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return this.id;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return this.userId;
    }

    public void setAiServiceId(Long aiServiceId) {
        this.aiServiceId = aiServiceId;
    }

    public Long getAiServiceId() {
        return this.aiServiceId;
    }

    public void setAiServiceKey(String aiServiceKey) {
        this.aiServiceKey = aiServiceKey;
    }

    public String getAiServiceKey() {
        return this.aiServiceKey;
    }

    public void setModelId(Long modelId) {
        this.modelId = modelId;
    }

    public Long getModelId() {
        return this.modelId;
    }

    public void setSearchServiceId(Long searchServiceId) {
        this.searchServiceId = searchServiceId;
    }

    public Long getSearchServiceId() {
        return this.searchServiceId;
    }

    public void setSearchServiceKey(String searchServiceKey) {
        this.searchServiceKey = searchServiceKey;
    }

    public String getSearchServiceKey() {
        return this.searchServiceKey;
    }

    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE).append("id", this.getId()).append("userId", this.getUserId()).append("aiServiceId", this.getAiServiceId()).append("aiServiceKey", this.getAiServiceKey()).append("modelId", this.getModelId()).append("searchServiceId", this.getSearchServiceId()).append("searchServiceKey", this.getSearchServiceKey()).toString();
    }
}
