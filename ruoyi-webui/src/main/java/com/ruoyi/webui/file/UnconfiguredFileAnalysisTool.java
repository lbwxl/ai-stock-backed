package com.ruoyi.webui.file;

import org.springframework.stereotype.Component;

@Component
public class UnconfiguredFileAnalysisTool implements FileAnalysisTool {
    @Override
    public String getId() { return "unconfigured"; }

    @Override
    public String analyze(FileAnalysisContext context, String task) {
        throw new FileAnalysisException(503, "FILE_TOOL_NOT_CONFIGURED",
                "当前模型判断需要外部文件分析能力，但第三方处理服务尚未配置，请联系管理员接入后重试");
    }
}
