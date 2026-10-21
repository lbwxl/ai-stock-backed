package com.ruoyi.web.controller.domain;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * 单文件上传的文档模型，实际响应仍由 CommonController 的 AjaxResult 返回。
 */
@ApiModel(description = "单文件上传成功响应；业务失败时仅返回 code 和 msg")
public class FileUploadResponse
{
    @ApiModelProperty(value = "业务状态码，200 表示成功", example = "200")
    public int code;

    @ApiModelProperty(value = "结果说明", example = "操作成功")
    public String msg;

    @ApiModelProperty(value = "文件访问地址", example = "http://127.0.0.1:18080/profile/upload/2026/10/21/example_001.txt")
    public String url;

    @ApiModelProperty(value = "文件资源路径，可用于下载接口", example = "/profile/upload/2026/10/21/example_001.txt")
    public String fileName;

    @ApiModelProperty(value = "服务端保存的文件名", example = "example_001.txt")
    public String newFileName;

    @ApiModelProperty(value = "客户端原始文件名", example = "example.txt")
    public String originalFilename;
}
