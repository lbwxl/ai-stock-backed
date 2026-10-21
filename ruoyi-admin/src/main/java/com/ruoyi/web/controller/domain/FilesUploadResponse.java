package com.ruoyi.web.controller.domain;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * 多文件上传的文档模型，保留原接口以逗号连接各文件结果的格式。
 */
@ApiModel(description = "多文件上传成功响应，各文件结果按提交顺序以逗号连接")
public class FilesUploadResponse
{
    @ApiModelProperty(value = "业务状态码，200 表示成功", example = "200")
    public int code;

    @ApiModelProperty(value = "结果说明", example = "操作成功")
    public String msg;

    @ApiModelProperty(value = "文件访问地址，逗号分隔", example = "http://127.0.0.1:18080/profile/upload/2026/10/21/a_001.txt,http://127.0.0.1:18080/profile/upload/2026/10/21/b_002.txt")
    public String urls;

    @ApiModelProperty(value = "文件资源路径，逗号分隔", example = "/profile/upload/2026/10/21/a_001.txt,/profile/upload/2026/10/21/b_002.txt")
    public String fileNames;

    @ApiModelProperty(value = "服务端文件名，逗号分隔", example = "a_001.txt,b_002.txt")
    public String newFileNames;

    @ApiModelProperty(value = "客户端原始文件名，逗号分隔", example = "a.txt,b.txt")
    public String originalFilenames;
}
