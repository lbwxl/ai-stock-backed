package com.ruoyi.webui.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.webui.domain.vo.AiServiceVo;
import com.ruoyi.webui.domain.vo.SysLoginVo;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Resolves credentials exclusively from the authenticated user's saved configuration. */
@Service
public class AiAnalysisService {
    private final IUserWebuiConfigService userConfigService;
    private final AiPromptService promptService;
    private final int connectTimeout;
    private final int readTimeout;

    public AiAnalysisService(IUserWebuiConfigService userConfigService, AiPromptService promptService,
            @Value("${webui.ai.connect-timeout:10000}") int connectTimeout,
            @Value("${webui.ai.read-timeout:120000}") int readTimeout) {
        this.userConfigService = userConfigService;
        this.promptService = promptService;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public HttpURLConnection openCompletion(Long userId, JSONObject request) throws IOException {
        return openModelRequest(userId, promptService.buildCompletion(request));
    }

    /** Internal server-to-server entry; file messages are assembled by the backend, never accepted from JSON APIs. */
    public HttpURLConnection openFileCompletion(Long userId, JSONObject body) throws IOException {
        return openModelRequest(userId, body);
    }

    public String currentModelName(Long userId) {
        SysLoginVo config = userConfigService.selectUserConfigByUserId(userId);
        return config == null || config.getAiService() == null ? null : config.getAiService().getModelName();
    }

    private HttpURLConnection openModelRequest(Long userId, JSONObject body) throws IOException {
        SysLoginVo config = userConfigService.selectUserConfigByUserId(userId);
        AiServiceVo ai = config == null ? null : config.getAiService();
        if (ai == null || StringUtils.isBlank(ai.getModelName())) {
            throw new ServiceException("当前用户尚未配置 AI 服务和模型，请联系管理员", 400);
        }
        if (!"ollama".equalsIgnoreCase(ai.getServiceName()) && StringUtils.isBlank(ai.getAiServiceKey())) {
            throw new ServiceException("当前用户尚未配置 AI 服务密钥，请联系管理员", 400);
        }
        String apiKey = StringUtils.trimToEmpty(ai.getAiServiceKey());
        if (apiKey.contains("\r") || apiKey.contains("\n")) {
            throw new ServiceException("AI 服务密钥格式不正确，请联系管理员", 400);
        }

        body.put("model", ai.getModelName());
        if ("openrouter".equalsIgnoreCase(ai.getServiceName())) {
            body.put("include_reasoning", true);
        }
        HttpURLConnection connection = (HttpURLConnection) completionUri(ai).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", Boolean.TRUE.equals(body.get("stream"))
                ? "text/event-stream" : "application/json");
        if (StringUtils.isNotBlank(apiKey)) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        byte[] payload = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            return connection;
        } catch (IOException | RuntimeException e) {
            connection.disconnect();
            throw e;
        }
    }

    private URI completionUri(AiServiceVo ai) {
        String base = ai.getServiceUrl();
        if (StringUtils.isBlank(base)) {
            String provider = StringUtils.defaultString(ai.getServiceName()).toLowerCase(java.util.Locale.ROOT);
            switch (provider) {
                case "openrouter": base = "https://openrouter.ai/api/v1"; break;
                case "deepseek": base = "https://api.deepseek.com/v1"; break;
                case "siliconflow": base = "https://api.siliconflow.cn/v1"; break;
                case "infiniai": base = "https://cloud.infini-ai.com/maas/v1"; break;
                case "ollama": base = "http://localhost:11434/v1"; break;
                case "openai-compatible": base = "https://api.openai.com/v1"; break;
                default: throw new ServiceException("AI 服务地址未配置，请联系管理员", 400);
            }
        }
        try {
            URI uri = URI.create(base.trim().replaceAll("/+$", ""));
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String url = uri.toString();
            return URI.create(url.endsWith("/chat/completions") ? url : url + "/chat/completions");
        } catch (IllegalArgumentException e) {
            throw new ServiceException("AI 服务地址格式不正确，请联系管理员", 400);
        }
    }
}
