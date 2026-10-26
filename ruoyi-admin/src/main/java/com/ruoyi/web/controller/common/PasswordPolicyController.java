package com.ruoyi.web.controller.common;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.PasswordPolicy;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Api(tags = "后台登录与权限")
public class PasswordPolicyController
{
    @Anonymous
    @GetMapping("/passwordPolicy")
    @ApiOperation("获取新密码规则，供注册及修改密码表单使用")
    public AjaxResult policy()
    {
        return AjaxResult.success(PasswordPolicy.describe());
    }
}
