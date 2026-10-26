package com.ruoyi.web.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.fasterxml.classmate.TypeResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.web.controller.domain.FileUploadResponse;
import com.ruoyi.web.controller.domain.FilesUploadResponse;
import io.swagger.models.auth.In;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.ApiKey;
import springfox.documentation.service.AuthorizationScope;
import springfox.documentation.service.Contact;
import springfox.documentation.service.SecurityReference;
import springfox.documentation.service.SecurityScheme;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.service.contexts.SecurityContext;
import springfox.documentation.spring.web.plugins.Docket;

/**
 * OpenAPI 3 接口文档配置
 * 
 * @author ruoyi
 */
@Configuration
public class SwaggerConfig
{
    /** 系统基础配置 */
    @Autowired
    private RuoYiConfig ruoyiConfig;

    /** 是否开启swagger */
    @Value("${swagger.enabled}")
    private boolean enabled;

    /** 设置请求的统一前缀 */
    @Value("${swagger.pathMapping}")
    private String pathMapping;

    /**
     * 创建API
     */
    @Bean
    public Docket createRestApi()
    {
        TypeResolver typeResolver = new TypeResolver();
        return new Docket(DocumentationType.OAS_30)
                // 是否启用Swagger
                .enable(enabled)
                // 用来创建该API的基本信息，展示在文档的页面中（自定义展示的信息）
                .apiInfo(apiInfo())
                .additionalModels(typeResolver.resolve(FileUploadResponse.class),
                        typeResolver.resolve(FilesUploadResponse.class))
                // 设置哪些接口暴露给Swagger展示
                .select()
                // 覆盖管理后台、二开业务、通用文件、定时任务及代码生成控制器。
                .apis(RequestHandlerSelectors.basePackage("com.ruoyi"))
                .paths(PathSelectors.any())
                .build()
                /* 设置安全模式，swagger可以设置访问token */
                .securitySchemes(securitySchemes())
                .securityContexts(securityContexts())
                .pathMapping(pathMapping);
    }

    /**
     * 安全模式，这里指定token通过Authorization头请求头传递
     */
    private List<SecurityScheme> securitySchemes()
    {
        List<SecurityScheme> apiKeyList = new ArrayList<SecurityScheme>();
        apiKeyList.add(new ApiKey("Authorization", "Authorization", In.HEADER.toValue()));
        return apiKeyList;
    }

    /**
     * 安全上下文
     */
    private List<SecurityContext> securityContexts()
    {
        List<SecurityContext> securityContexts = new ArrayList<>();
        securityContexts.add(
                SecurityContext.builder()
                        .securityReferences(defaultAuth())
                        .operationSelector(o -> requiresAuthorization(o.requestMappingPattern()))
                        .build());
        return securityContexts;
    }

    private boolean requiresAuthorization(String requestPath)
    {
        if (pathMapping != null && !pathMapping.isEmpty() && requestPath.startsWith(pathMapping + "/"))
        {
            requestPath = requestPath.substring(pathMapping.length());
        }
        return !Arrays.asList("/", "/login", "/register", "/captchaImage", "/passwordPolicy").contains(requestPath);
    }

    /**
     * 默认的安全上引用
     */
    private List<SecurityReference> defaultAuth()
    {
        AuthorizationScope authorizationScope = new AuthorizationScope("global", "accessEverything");
        AuthorizationScope[] authorizationScopes = new AuthorizationScope[1];
        authorizationScopes[0] = authorizationScope;
        List<SecurityReference> securityReferences = new ArrayList<>();
        securityReferences.add(new SecurityReference("Authorization", authorizationScopes));
        return securityReferences;
    }

    /**
     * 添加摘要信息
     */
    private ApiInfo apiInfo()
    {
        // 用ApiInfoBuilder进行定制
        return new ApiInfoBuilder()
                // 设置标题
                .title("AI 服务管理后台接口文档")
                // 描述
                .description("包含系统管理、监控、代码生成、AI 服务、模型、搜索服务、用户配置及文件上传下载。"
                        + "\n\n在线调试：先调用 GET /captchaImage 获取验证码和 uuid，再通过 POST /login 登录；"
                        + "点击 Authorize，填写 Bearer 加空格及返回的 token。"
                        + "\n\n通用上传使用 multipart/form-data：单文件字段 file，多文件字段 files。"
                        + "默认单文件最大 10MB，单次请求最大 20MB，支持文件类型白名单校验，不支持分片上传。"
                        + "\n\nGET /webui/config/current 需要 Bearer token，只返回当前登录用户的 AI 与搜索配置。"
                        + "原 POST /webui/auth/in 已停用，统一使用系统 POST /login 登录。")
                // 作者信息
                .contact(new Contact(ruoyiConfig.getName(), null, null))
                // 版本
                .version("版本号:" + ruoyiConfig.getVersion())
                .build();
    }
}
