package com.ruoyi.webui.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.webui.file.FileAnalysisContext;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/** Owns every instruction/template. Requests contain business data, never provider messages. */
@Service
public class AiPromptService {
    private static final Pattern SLOT = Pattern.compile("\\{\\{(\\w+)\\}\\}");
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("scene", "query", "language",
            "searchLanguage", "questionCount", "queryCount", "learningCount", "followUpCount", "feedback",
            "images", "learnings", "results", "researchGoal", "followUpQuestions", "stream"));
    private final Map<String, String> templates = new HashMap<>();
    private final Clock clock;

    public AiPromptService() {
        this(Clock.systemUTC());
    }

    public AiPromptService(Clock clock) {
        this.clock = clock;
        for (String name : Arrays.asList("system", "feedback", "search-queries", "process-results", "report",
                "file-analysis-system", "file-analysis")) {
            try (InputStream input = new ClassPathResource("prompts/ai/" + name + ".txt").getInputStream()) {
                templates.put(name, StreamUtils.copyToString(input, StandardCharsets.UTF_8).trim());
            } catch (IOException e) {
                throw new IllegalStateException("无法读取 AI 提示词模板: " + name, e);
            }
        }
    }

    public JSONObject buildCompletion(JSONObject request) {
        if (request == null || !FIELDS.containsAll(request.keySet())) {
            throw bad("仅接受业务分析参数，不接受 messages、system、prompt、模型或生成参数");
        }
        String scene = requiredText(request.get("scene"), "scene");
        if (!Arrays.asList("feedback", "search_queries", "process_results", "report").contains(scene)) {
            throw bad("不支持的 AI 分析场景");
        }
        if (request.containsKey("stream") && !(request.get("stream") instanceof Boolean)) {
            throw bad("stream 必须为布尔值");
        }
        String language = language(request.getOrDefault("language", "zh"));
        Map<String, String> values = new HashMap<>();
        values.put("now", Instant.now(clock).toString());
        values.put("query", combinedQuery(request));
        values.put("language", "Respond in " + language + "." + ("中文".equals(language)
                ? " 在中文和英文之间添加适当的空格来提升可读性" : ""));
        String template;
        switch (scene) {
            case "feedback":
                template = "feedback";
                values.put("questionCount", count(request, "questionCount", 3, 1, 5));
                break;
            case "search_queries":
                template = "search-queries";
                values.put("queryCount", count(request, "queryCount", 3, 1, 10));
                String goal = optionalText(request.get("researchGoal"), "researchGoal");
                String directions = joinTexts(list(request, "followUpQuestions", false), "followUpQuestions");
                values.put("researchContext", goal.isEmpty() && directions.isEmpty() ? ""
                        : "Previous research goal: " + goal + "\nFollow-up research directions:\n" + directions);
                String learnings = joinTexts(list(request, "learnings", false), "learnings");
                values.put("learnings", learnings.isEmpty() ? "" : "Here are some learnings from previous research, "
                        + "use them to generate more specific queries:\n" + learnings);
                String searchLanguage = request.get("searchLanguage") == null ? "" : language(request.get("searchLanguage"));
                values.put("searchLanguage", searchLanguage.isEmpty() || searchLanguage.equals(language) ? ""
                        : "Use " + searchLanguage + " for the SERP queries.");
                break;
            case "process_results":
                template = "process-results";
                values.put("learningCount", count(request, "learningCount", 5, 1, 20));
                values.put("followUpCount", count(request, "followUpCount", 3, 0, 10));
                StringBuilder contents = new StringBuilder();
                for (Object item : list(request, "results", true)) {
                    Map<?, ?> result = object(item, "results");
                    contents.append("<content url=\"").append(attribute(requiredText(result.get("url"), "results.url")))
                            .append("\">\n").append(requiredText(result.get("content"), "results.content"))
                            .append("\n</content>\n");
                }
                values.put("contents", contents.toString());
                break;
            default:
                template = "report";
                StringBuilder reportLearnings = new StringBuilder();
                int index = 1;
                for (Object item : list(request, "learnings", true)) {
                    Map<?, ?> learning = object(item, "learnings");
                    requiredText(learning.get("url"), "learnings.url");
                    reportLearnings.append("<learning index=\"").append(index++).append("\">\n")
                            .append(requiredText(learning.get("learning"), "learnings.learning"))
                            .append("\n</learning>\n");
                }
                values.put("learnings", reportLearnings.toString());
        }
        JSONArray messages = new JSONArray();
        messages.add(message("system", render("system", values)));
        String userPrompt = render(template, values);
        List<?> images = list(request, "images", false);
        if (!images.isEmpty() && !"feedback".equals(scene)) {
            throw bad("图片仅用于补充问题场景");
        }
        if (images.isEmpty()) {
            messages.add(message("user", userPrompt));
        } else {
            if (images.size() > 20) { throw bad("一次最多提交 20 张图片"); }
            JSONArray content = new JSONArray();
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", userPrompt);
            content.add(text);
            for (Object image : images) {
                String url = requiredText(image, "images");
                if (!url.matches("(?s)^data:image/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=\\r\\n]+$")) {
                    throw bad("图片必须为 PNG、JPEG、WEBP 或 GIF 的 Base64 数据");
                }
                JSONObject imageUrl = new JSONObject();
                imageUrl.put("url", url);
                imageUrl.put("detail", "high");
                JSONObject part = new JSONObject();
                part.put("type", "image_url");
                part.put("image_url", imageUrl);
                content.add(part);
            }
            messages.add(message("user", content));
        }
        JSONObject body = new JSONObject();
        body.put("messages", messages);
        body.put("stream", Boolean.TRUE.equals(request.get("stream")));
        body.put("temperature", 0);
        return body;
    }

    /** Sends a URL for provider-side fetching; never embeds or interprets file contents. */
    public JSONObject buildFileCompletion(FileAnalysisContext file, boolean attachOriginal, String attachmentFailure) {
        Map<String, String> values = new HashMap<>();
        values.put("fileName", file.getFileName());
        values.put("mimeType", file.getMimeType());
        values.put("fileSize", Long.toString(file.getFileSize()));
        values.put("fileUrl", file.getFileUrl());
        values.put("processingRequirement", file.getProcessingRequirement());
        values.put("analysisRequirement", file.getAnalysisRequirement());
        values.put("attachmentStatus", attachOriginal ? "本次已提供临时授权文件 URL，请直接读取链接中的原文件，自行判断能否处理。" : attachmentFailure);
        JSONArray messages = new JSONArray();
        messages.add(message("system", render("file-analysis-system", values)));
        String prompt = render("file-analysis", values);
        if (attachOriginal) {
            JSONArray content = new JSONArray();
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", prompt);
            content.add(text);
            String data = file.getFileUrl();
            JSONObject attachment = new JSONObject();
            JSONObject payload = new JSONObject();
            // This is protocol serialization, not a capability decision or an analysis route.
            if (Arrays.asList("image/png", "image/jpeg", "image/gif", "image/webp").contains(file.getMimeType())) {
                attachment.put("type", "image_url");
                payload.put("url", data);
                attachment.put("image_url", payload);
            } else {
                attachment.put("type", "file");
                payload.put("filename", file.getFileName());
                payload.put("file_data", data);
                attachment.put("file", payload);
            }
            content.add(attachment);
            messages.add(message("user", content));
        } else {
            messages.add(message("user", prompt));
        }
        JSONObject task = new JSONObject();
        task.put("type", "string");
        task.put("description", "说明需要外部服务对当前上传的原始文件完成的具体任务");
        JSONObject fields = new JSONObject();
        fields.put("task", task);
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        parameters.put("properties", fields);
        parameters.put("required", Arrays.asList("task"));
        parameters.put("additionalProperties", false);
        JSONObject function = new JSONObject();
        function.put("name", "analyze_uploaded_file");
        function.put("description", "需要外部文件分析服务或专用模型时调用。服务接收本次上传文件的临时授权 URL；无需传文件内容、地址或用户身份。服务可能尚未配置。");
        function.put("parameters", parameters);
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", function);
        JSONObject body = new JSONObject();
        body.put("messages", messages);
        body.put("tools", Arrays.asList(tool));
        body.put("tool_choice", "auto");
        body.put("parallel_tool_calls", false);
        body.put("stream", false);
        body.put("temperature", 0);
        return body;
    }

    private String combinedQuery(JSONObject request) {
        String query = requiredText(request.get("query"), "query");
        List<?> feedback = list(request, "feedback", false);
        if (feedback.isEmpty()) { return query; }
        StringBuilder combined = new StringBuilder("Initial Query: ").append(query)
                .append("\nFollow-up Questions and Answers:\n");
        for (Object item : feedback) {
            Map<?, ?> answer = object(item, "feedback");
            combined.append("Q: ").append(requiredText(answer.get("question"), "feedback.question"))
                    .append("\nA: ").append(requiredText(answer.get("answer"), "feedback.answer")).append('\n');
        }
        return combined.toString();
    }

    private String render(String template, Map<String, String> values) {
        Matcher matcher = SLOT.matcher(templates.get(template));
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) { throw new IllegalStateException("提示词模板参数缺失"); }
            matcher.appendReplacement(output, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String language(Object value) {
        String name = requiredText(value, "language");
        switch (name) {
            case "zh": case "中文": return "中文";
            case "en": case "English": return "English";
            case "nl": case "Nederlands": return "Nederlands";
            default: throw bad("仅支持 zh、en、nl 三种语言");
        }
    }

    private String count(JSONObject request, String key, int fallback, int min, int max) {
        if (!request.containsKey(key)) { return Integer.toString(fallback); }
        Object value = request.get(key);
        if (!(value instanceof Number)) { throw bad(key + " 必须为整数"); }
        try {
            int number = new BigDecimal(value.toString()).intValueExact();
            if (number < min || number > max) { throw bad(key + " 必须在 " + min + " 到 " + max + " 之间"); }
            return Integer.toString(number);
        } catch (ArithmeticException | NumberFormatException e) {
            throw bad(key + " 必须为整数");
        }
    }

    private List<?> list(JSONObject request, String key, boolean required) {
        Object value = request.get(key);
        if (value == null && !required) { return java.util.Collections.emptyList(); }
        if (!(value instanceof List) || (required && ((List<?>) value).isEmpty())) { throw bad(key + " 必须为非空数组"); }
        return (List<?>) value;
    }

    private Map<?, ?> object(Object value, String key) {
        if (!(value instanceof Map)) { throw bad(key + " 数组元素必须为对象"); }
        return (Map<?, ?>) value;
    }

    private String joinTexts(List<?> items, String key) {
        StringBuilder joined = new StringBuilder();
        for (Object item : items) { joined.append(requiredText(item, key)).append('\n'); }
        return joined.toString().trim();
    }

    private String optionalText(Object value, String key) {
        return value == null ? "" : requiredText(value, key);
    }

    private String requiredText(Object value, String key) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) { throw bad(key + " 不能为空且必须为字符串"); }
        return (String) value;
    }

    private String attribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JSONObject message(String role, Object content) {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private ServiceException bad(String message) { return new ServiceException(message, 400); }
}
