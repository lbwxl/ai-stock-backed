package com.ruoyi.webui.file;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.webui.service.AiAnalysisService;
import com.ruoyi.webui.service.AiPromptService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Executes the user's model decisions. Does not inspect/extract file contents or choose a different model. */
@Service
public class FileAnalysisService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileAnalysisService.class);
    public static final String TOOL_NAME = "analyze_uploaded_file";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final AiAnalysisService ai;
    private final AiPromptService prompts;
    private final FileAnalysisProperties properties;
    private final Map<String, FileAnalysisTool> tools = new HashMap<>();

    public FileAnalysisService(AiAnalysisService ai, AiPromptService prompts, FileAnalysisProperties properties,
            List<FileAnalysisTool> handlers) {
        this.ai = ai;
        this.prompts = prompts;
        this.properties = properties;
        for (FileAnalysisTool handler : handlers) {
            if (tools.put(handler.getId(), handler) != null) { throw new IllegalStateException("文件工具 ID 重复"); }
        }
    }

    public FileAnalysisResult analyze(Long userId, String requestId, FileAnalysisReference file,
            String processing, String analysis) {
        return analyzeTracked(userId, requestId, file, processing, analysis, false);
    }

    public FileAnalysisResult analyzeForTask(Long userId, String requestId, FileAnalysisReference file,
            String processing, String analysis) {
        return analyzeTracked(userId, requestId, file, processing, analysis, true);
    }

    public String currentModelName(Long userId) { return ai.currentModelName(userId); }

    private FileAnalysisResult analyzeTracked(Long userId, String requestId, FileAnalysisReference file,
            String processing, String analysis, boolean manualOnTool) {
        long start = System.nanoTime();
        log.info("stage=analysis_started requestId={} userId={} mimeType={} size={}", requestId, userId, file.mimeType, file.size);
        try {
            FileAnalysisResult result = analyzeFile(userId, requestId, file, processing, analysis, manualOnTool);
            log.info("stage=analysis_completed requestId={} resultChars={} durationMs={}", requestId,
                    result.analysisText.length(), (System.nanoTime() - start) / 1000000);
            return result;
        } catch (FileAnalysisException e) {
            log.warn("stage=analysis_failed requestId={} httpStatus={} code={} durationMs={}", requestId,
                    e.getStatus(), e.getCode(), (System.nanoTime() - start) / 1000000);
            throw e;
        }
    }

    private FileAnalysisResult analyzeFile(Long userId, String requestId, FileAnalysisReference file,
            String processing, String analysis, boolean manualOnTool) {
        if (processing == null || processing.trim().isEmpty() || analysis == null || analysis.trim().isEmpty()) {
            throw failure(400, "INVALID_REQUEST", "文件处理要求和内容分析要求不能为空");
        }
        if (processing.length() > 10000 || analysis.length() > 10000) {
            throw failure(400, "INVALID_REQUEST", "处理或分析要求过长，请控制在 10000 字以内");
        }
        FileAnalysisContext context = new FileAnalysisContext(userId, requestId, file.fileName, file.mimeType,
                file.url, file.size, processing.trim(), analysis.trim());
        checkFileUrl(context, file.validationUrl);
        JSONObject body = prompts.buildFileCompletion(context, true, "");
        boolean urlAttachment = true;
        boolean canRetryAttachment = true;
        int toolRounds = 0;
        while (true) {
            JSONObject message;
            try {
                message = completion(context.getUserId(), body);
            } catch (AttachmentRejected rejected) {
                log.info("stage=model_attachment_rejected httpStatus={} retryAllowed={}", rejected.status, canRetryAttachment);
                // A tunnel can fail between the initial HEAD and the provider fetching the file.
                // Do not misreport an unreachable URL as a missing model capability or tool.
                checkFileUrl(context, file.validationUrl);
                if (!canRetryAttachment) {
                    throw failure(502, "MODEL_REQUEST_REJECTED", "当前模型接口拒绝文件分析请求，请检查模型的工具调用支持或平台适配");
                }
                // The provider rejected the URL attachment before the model could decide.
                // Return this fact (not extracted content) to the SAME model, with the same tool available.
                urlAttachment = false;
                canRetryAttachment = false;
                body = prompts.buildFileCompletion(context, false,
                        "上一携带文件 URL 附件的请求被模型接口拒绝（HTTP " + rejected.status + "）。本次只提供文件元信息，未提供文件内容。");
                log.info("stage=model_retry_without_attachment");
                continue;
            }
            canRetryAttachment = false;
            JSONArray calls = message.getJSONArray("tool_calls");
            if (calls == null || calls.isEmpty()) {
                String text = message.get("content") instanceof String ? message.getString("content") : null;
                if (text == null || text.trim().isEmpty()) { throw failure(502, "EMPTY_ANALYSIS", "模型未返回文件分析结果"); }
                if (!urlAttachment && toolRounds == 0) {
                    throw failure(422, "MODEL_CANNOT_READ_FILE",
                            "模型接口无法读取文件 URL 附件，且模型未调用外部文件分析工具，请使用支持附件的模型或接入文件分析服务");
                }
                return new FileAnalysisResult(requestId, context.getFileName(), text.trim());
            }
            int maxRounds = Math.max(1, Math.min(5, properties.getMaxToolRounds()));
            if (++toolRounds > maxRounds) { throw failure(502, "TOOL_LIMIT_REACHED", "模型文件工具调用次数过多，请调整要求后重试"); }
            if (calls.size() != 1) { throw failure(502, "INVALID_TOOL_CALL", "模型必须逐次调用文件分析工具"); }
            JSONObject call = calls.getJSONObject(0);
            JSONObject function = call == null ? null : call.getJSONObject("function");
            if (function == null || !TOOL_NAME.equals(function.getString("name"))
                    || call.getString("id") == null || call.getString("id").trim().isEmpty()) {
                throw failure(502, "INVALID_TOOL_CALL", "模型返回了不支持的文件工具调用");
            }
            String task = task(function.get("arguments"));
            if (manualOnTool) {
                throw failure(503, "FILE_MANUAL_REVIEW_REQUIRED", "当前文件需要后台人工维护分析结果");
            }
            FileAnalysisTool handler = tools.get(properties.getToolHandler());
            log.info("stage=tool_requested tool={} round={}", TOOL_NAME, toolRounds);
            if (handler == null) { throw failure(503, "FILE_TOOL_NOT_CONFIGURED", "模型请求的文件分析工具尚未配置，请联系管理员"); }
            long toolStart = System.nanoTime();
            String toolResult = handler.analyze(context, task);
            if (toolResult == null || toolResult.trim().isEmpty() || toolResult.length() > MAX_RESPONSE_BYTES) {
                throw failure(502, "INVALID_TOOL_RESULT", "文件处理服务未返回有效结果");
            }
            log.info("stage=tool_completed resultChars={} durationMs={}", toolResult.length(), (System.nanoTime() - toolStart) / 1000000);
            JSONObject assistant = new JSONObject();
            assistant.put("role", "assistant");
            assistant.put("content", message.get("content"));
            assistant.put("tool_calls", calls);
            if (message.get("reasoning_content") instanceof String) {
                assistant.put("reasoning_content", message.get("reasoning_content"));
            }
            JSONArray messages = body.getJSONArray("messages");
            messages.add(assistant);
            JSONObject toolMessage = new JSONObject();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", call.getString("id"));
            toolMessage.put("content", toolResult);
            messages.add(toolMessage);
        }
    }

    /** Transport validation only: HEAD never reads or extracts the uploaded file body. */
    private void checkFileUrl(FileAnalysisContext context, String validationUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(validationUrl).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("HEAD");
            int status = connection.getResponseCode();
            String type = connection.getContentType();
            log.info("stage=file_url_checked httpStatus={} expectedBytes={} actualBytes={} mimeMatches={}", status,
                    context.getFileSize(), connection.getContentLengthLong(), type != null && context.getMimeType().equalsIgnoreCase(type.split(";", 2)[0].trim()));
            if (status != 200 || type == null
                    || !context.getMimeType().equalsIgnoreCase(type.split(";", 2)[0].trim())
                    || connection.getContentLengthLong() != context.getFileSize()) {
                throw unreachable();
            }
        } catch (IOException | IllegalArgumentException e) { throw unreachable(); }
        finally { if (connection != null) { connection.disconnect(); } }
    }

    private FileAnalysisException unreachable() {
        return failure(503, "FILE_URL_UNREACHABLE", "文件访问地址暂时不可达，请检查文件存储服务或网络，稍后重试；无需重新上传文件");
    }

    private JSONObject completion(Long userId, JSONObject body) {
        HttpURLConnection connection = null;
        long start = System.nanoTime();
        log.info("stage=model_request_started userId={}", userId);
        try {
            connection = ai.openFileCompletion(userId, body);
            int status = connection.getResponseCode();
            String model = String.valueOf(body.get("model")).replaceAll("[\\r\\n\\t]", "_");
            log.info("stage=model_response model={} httpStatus={} durationMs={}", model.substring(0, Math.min(100, model.length())),
                    status, (System.nanoTime() - start) / 1000000);
            if (status == 400 || status == 415 || status == 422) { throw new AttachmentRejected(status); }
            if (status < 200 || status >= 300) {
                if (status == 429) { throw failure(429, "MODEL_RATE_LIMITED", "模型请求过于频繁或额度不足，请稍后重试"); }
                if (status == 401 || status == 403) { throw failure(502, "MODEL_AUTH_FAILED", "模型服务认证失败，请联系管理员检查用户配置"); }
                throw failure(502, "MODEL_REQUEST_FAILED", "文件分析模型请求失败（HTTP " + status + "），请检查服务配置");
            }
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                throw failure(502, "INVALID_MODEL_RESPONSE", "模型返回格式不正确");
            }
            byte[] bytes;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (output.size() + length > MAX_RESPONSE_BYTES) { throw failure(502, "INVALID_MODEL_RESPONSE", "模型返回内容过大"); }
                    output.write(buffer, 0, length);
                }
                bytes = output.toByteArray();
            }
            try {
                JSONObject response = JSONObject.parseObject(new String(bytes, StandardCharsets.UTF_8));
                JSONArray choices = response.getJSONArray("choices");
                if (choices != null && !choices.isEmpty() && "length".equals(choices.getJSONObject(0).getString("finish_reason"))) {
                    throw failure(502, "ANALYSIS_INCOMPLETE", "模型输出被截断，请缩小分析范围后重试");
                }
                JSONObject message = choices == null || choices.isEmpty() ? null : choices.getJSONObject(0).getJSONObject("message");
                if (message == null) { throw failure(502, "INVALID_MODEL_RESPONSE", "模型没有返回有效消息"); }
                return message;
            } catch (FileAnalysisException e) { throw e; }
            catch (RuntimeException e) { throw failure(502, "INVALID_MODEL_RESPONSE", "模型返回内容无法解析"); }
        } catch (ServiceException e) {
            throw failure(e.getCode() == null ? 400 : e.getCode(), "MODEL_NOT_CONFIGURED", e.getMessage());
        } catch (SocketTimeoutException e) { throw failure(504, "ANALYSIS_TIMEOUT", "文件分析超时，请稍后重试"); }
        catch (IOException e) { throw failure(502, "MODEL_UNAVAILABLE", "无法连接文件分析模型，请稍后重试"); }
        finally { if (connection != null) { connection.disconnect(); } }
    }

    private String task(Object arguments) {
        try {
            if (!(arguments instanceof String) || ((String) arguments).length() > 20000) { throw new IllegalArgumentException(); }
            JSONObject json = JSONObject.parseObject((String) arguments);
            if (json.size() != 1 || !(json.get("task") instanceof String)) { throw new IllegalArgumentException(); }
            String value = json.getString("task").trim();
            if (value.isEmpty() || value.length() > 10000) { throw new IllegalArgumentException(); }
            return value;
        } catch (RuntimeException e) { throw failure(502, "INVALID_TOOL_CALL", "模型文件工具参数不合法"); }
    }

    private FileAnalysisException failure(int status, String code, String message) {
        return new FileAnalysisException(status, code, message);
    }

    private static final class AttachmentRejected extends RuntimeException {
        private final int status;
        private AttachmentRejected(int status) { this.status = status; }
    }
}
