package com.ruoyi.web.controller.domain;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel(description = "WebUI 用户配置登录参数")
public class AuthDto {
    @ApiModelProperty(value = "用户名，长度 2-20", required = true)
    private String username;
    @ApiModelProperty(value = "密码，长度 5-20", required = true)
    private String password;

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return this.password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
