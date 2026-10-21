package com.ruoyi.webui.domain.vo;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public class AiModelVo extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long id;
    @Excel(name="模型名称")
    private String modelName;
    @Excel(name="AI服务ID，关联ai_service表")
    private Long aiServiceId;
    @Excel(name="服务名称")
    private String serviceName;

    public String getServiceName() {
        return this.serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return this.id;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelName() {
        return this.modelName;
    }

    public void setAiServiceId(Long aiServiceId) {
        this.aiServiceId = aiServiceId;
    }

    public Long getAiServiceId() {
        return this.aiServiceId;
    }

    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE).append("id", this.getId()).append("modelName", this.getModelName()).append("aiServiceId", this.getAiServiceId()).toString();
    }
}
