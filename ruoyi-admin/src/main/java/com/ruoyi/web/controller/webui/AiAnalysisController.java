package com.ruoyi.web.controller.webui;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.webui.service.AiAnalysisService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "AI 分析")
@RestController
@RequestMapping("/webui/ai")
public class AiAnalysisController extends BaseController {
    private final AiAnalysisService analysisService;

    public AiAnalysisController(AiAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @ApiOperation(value = "使用当前用户配置执行 AI 分析", notes = "需要 Bearer JWT。请求仅接受 scene、query 等业务数据；系统提示词、业务模板、模型和密钥均由后端决定。支持 feedback、search_queries、process_results、report 四种场景，响应为 JSON 或 SSE。")
    @PostMapping("/chat/completions")
    public void complete(@RequestBody JSONObject request, HttpServletResponse response) throws IOException {
        Long userId = getUserId();
        HttpURLConnection upstream = null;
        try {
            upstream = analysisService.openCompletion(userId, request);
            int status = upstream.getResponseCode();
            if (status < 200 || status >= 300) {
                // Provider errors may echo keys or prompts. Return a safe, actionable message instead.
                if (status >= 300 && status < 400) {
                    writeError(response, 502, "AI 服务地址发生重定向，请在后台配置供应商最终的 HTTPS 接口地址");
                } else if (status == 401 || status == 403) {
                    writeError(response, 502, "AI 服务认证失败，请联系管理员检查当前用户的服务密钥");
                } else if (status == 429) {
                    writeError(response, 429, "AI 服务请求过于频繁或额度不足，请稍后重试");
                } else {
                    writeError(response, 502, "AI 服务调用失败（状态码 " + status + "），请检查模型配置或稍后重试");
                }
                return;
            }
            boolean streaming = Boolean.TRUE.equals(request.get("stream"));
            String contentType = upstream.getContentType();
            if (contentType == null || (streaming
                    ? !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")
                    : !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))) {
                writeError(response, 502, "AI 服务返回了不支持的响应格式");
                return;
            }
            response.setContentType(streaming ? "text/event-stream;charset=UTF-8" : "application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("X-Accel-Buffering", "no");
            try (InputStream input = upstream.getInputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    response.getOutputStream().write(buffer, 0, length);
                    if (streaming) {
                        response.flushBuffer();
                    }
                }
            }
        } catch (ServiceException e) {
            writeError(response, e.getCode() == null ? 400 : e.getCode(), e.getMessage());
        } catch (SocketTimeoutException e) {
            handleTransportError(response, 504, "AI 服务响应超时，请稍后重试");
        } catch (IOException e) {
            handleTransportError(response, 502, "AI 服务连接中断，请检查服务配置或稍后重试");
        } finally {
            if (upstream != null) {
                upstream.disconnect();
            }
        }
    }

    private void handleTransportError(HttpServletResponse response, int status, String message) throws IOException {
        if (!response.isCommitted()) {
            writeError(response, status, message);
        } else if (response.getContentType() != null && response.getContentType().startsWith("text/event-stream")) {
            // Once SSE starts its HTTP status cannot change. Emit a protocol error instead of a partial success.
            try {
                String event = "data: " + errorBody(message).toJSONString() + "\n\n";
                response.getOutputStream().write(event.getBytes(StandardCharsets.UTF_8));
                response.flushBuffer();
            } catch (IOException ignored) {
                // A disconnected browser cannot receive an error; finally still closes the upstream request.
            }
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(errorBody(message).toJSONString().getBytes(StandardCharsets.UTF_8));
    }

    private JSONObject errorBody(String message) {
        JSONObject error = new JSONObject();
        error.put("message", message);
        error.put("type", "ai_service_error");
        JSONObject body = new JSONObject();
        body.put("error", error);
        body.put("msg", message);
        return body;
    }
}
