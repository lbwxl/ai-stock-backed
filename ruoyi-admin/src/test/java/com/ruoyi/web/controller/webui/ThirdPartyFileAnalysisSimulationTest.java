package com.ruoyi.web.controller.webui;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.webui.domain.vo.AiServiceVo;
import com.ruoyi.webui.domain.vo.SysLoginVo;
import com.ruoyi.webui.file.*;
import com.ruoyi.webui.service.AiAnalysisService;
import com.ruoyi.webui.service.AiPromptService;
import com.ruoyi.webui.service.IUserWebuiConfigService;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Opt-in live simulation. Registers a tool only in this test instance; leaves application configuration untouched. */
public class ThirdPartyFileAnalysisSimulationTest {
    @Test public void currentUserModelDelegatesUnreadablePdfToRealThirdPartyService() throws Exception {
        Assume.assumeTrue("Explicit opt-in required for paid external API calls", "true".equals(System.getenv("FILE_ANALYSIS_SIMULATION_LIVE")));
        JSONObject settings = JSONObject.parseObject(new String(Files.readAllBytes(Paths.get(System.getenv("FILE_ANALYSIS_SIMULATION_CONFIG"))), StandardCharsets.UTF_8));
        Long userId = settings.getLong("userId");
        JSONObject primary = settings.getJSONObject("primary");
        JSONObject fixture = settings.getJSONObject("fixture");
        List<JSONObject> events = new ArrayList<>();
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AiServiceVo configured = new AiServiceVo();
        configured.setServiceName(primary.getString("serviceName"));
        configured.setServiceUrl(primary.getString("baseUrl"));
        configured.setModelName(primary.getString("model"));
        configured.setAiServiceKey(primary.getString("apiKey"));
        SysLoginVo userConfig = new SysLoginVo(); userConfig.setAiService(configured);
        IUserWebuiConfigService configs = (IUserWebuiConfigService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IUserWebuiConfigService.class }, (proxy, method, args) -> {
                    if (!"selectUserConfigByUserId".equals(method.getName())) throw new UnsupportedOperationException();
                    assertEquals(userId, args[0]); return userConfig;
                });
        AiPromptService prompts = new AiPromptService();
        AiAnalysisService ai = new AiAnalysisService(configs, prompts, 10000, 120000) {
            @Override public HttpURLConnection openFileCompletion(Long owner, JSONObject body) throws IOException {
                primaryCalls.incrementAndGet();
                HttpURLConnection connection = super.openFileCompletion(owner, body);
                int status = connection.getResponseCode();
                if (status == 400 || status == 415 || status == 422) rejections.incrementAndGet();
                JSONObject event = new JSONObject(); event.put("stage", "primary_model"); event.put("httpStatus", status);
                event.put("requestNumber", primaryCalls.get()); events.add(event);
                System.out.println("SIM primary_model request=" + primaryCalls.get() + " status=" + status);
                return connection;
            }
        };
        FileAnalysisProperties properties = new FileAnalysisProperties();
        if ("minio".equals(settings.getString("storageType"))) {
            properties.setStorageType("minio");
            properties.getMinio().setConfigFile(settings.getString("minioConfigFile"));
        }
        properties.setStoragePath(settings.getString("storagePath"));
        properties.setPublicBaseUrlFile(settings.getString("publicBaseUrlFile"));
        properties.setToolHandler("third-party-simulation");
        FileAnalysisStorage storage = new FileAnalysisStorage(properties);
        FileAnalysisTool tool = new FileAnalysisTool() {
            public String getId() { return "third-party-simulation"; }
            public String analyze(FileAnalysisContext context, String task) {
                toolCalls.incrementAndGet();
                assertEquals(userId, context.getUserId());
                assertFalse(task.trim().isEmpty());
                JSONObject body = new JSONObject();
                body.put("requestId", context.getRequestId()); body.put("fileUrl", context.getFileUrl());
                body.put("fileName", context.getFileName()); body.put("mimeType", context.getMimeType());
                body.put("processingRequirement", context.getProcessingRequirement());
                body.put("analysisRequirement", context.getAnalysisRequirement()); body.put("task", task);
                JSONObject event = new JSONObject(); event.put("stage", "model_invoked_file_tool"); events.add(event);
                System.out.println("SIM model invoked external file tool");
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(fixture.getString("baseUrl") + "/analyze").openConnection();
                    connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST");
                    connection.setConnectTimeout(5000); connection.setReadTimeout(260000); connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setRequestProperty("Authorization", "Bearer " + fixture.getString("apiKey"));
                    try (OutputStream output = connection.getOutputStream()) { output.write(body.toJSONString().getBytes(StandardCharsets.UTF_8)); }
                    int status = connection.getResponseCode();
                    InputStream source = status == 200 ? connection.getInputStream() : connection.getErrorStream();
                    String raw;
                    try (InputStream input = source) { raw = org.springframework.util.StreamUtils.copyToString(input, StandardCharsets.UTF_8); }
                    JSONObject response = JSONObject.parseObject(raw);
                    if (status != 200) throw new FileAnalysisException(502, "SIMULATION_THIRD_PARTY_FAILED", response.getString("code"));
                    String text = response.getString("analysisText");
                    if (text == null || text.trim().isEmpty()) throw new FileAnalysisException(502, "SIMULATION_EMPTY_RESULT", "empty");
                    event = new JSONObject(); event.put("stage", "third_party_result_received"); events.add(event);
                    return text;
                } catch (IOException e) { throw new FileAnalysisException(502, "SIMULATION_THIRD_PARTY_UNAVAILABLE", "Local simulation service failed"); }
                finally { if (connection != null) connection.disconnect(); }
            }
        };
        FileAnalysisService service = new FileAnalysisService(ai, prompts, properties, Arrays.asList(tool));
        LoginUser identity = new LoginUser(); identity.setUserId(userId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity, "test-only-auth-context"));
        String verification = Integer.toString(new SecureRandom().nextInt(900000) + 100000);
        byte[] pdf = pdf(verification);
        String fileUrl = null;
        JSONObject report = new JSONObject();
        report.put("success", false); report.put("startedAt", java.time.Instant.now().toString());
        report.put("testOnly", true); report.put("primaryModel", primary.getString("model"));
        report.put("thirdPartyBaseUrl", settings.getJSONObject("thirdParty").getString("baseUrl"));
        report.put("thirdPartyModel", settings.getJSONObject("thirdParty").getString("model"));
        report.put("events", events);
        try {
            fileUrl = storage.upload(userId, new MockMultipartFile("file", "delegation-check.pdf", "application/pdf", pdf)).getString("fileUrl");
            waitForSignedFile(storage, userId, fileUrl);
            JSONObject body = new JSONObject(); body.put("fileUrl", fileUrl);
            body.put("processingRequirement", "保持原文件，读取 PDF。若当前模型无法直接读取此文件，请调用外部文件分析工具处理，不要猜测内容。");
            body.put("analysisRequirement", "返回 PDF 页面中的 ACCESS CODE 数字，并用一句话说明文件内容。数字只存在于文件中，请基于实际读取结果回答。");
            org.springframework.mock.web.MockHttpServletResponse response = MockMvcBuilders.standaloneSetup(new FileAnalysisController(service, storage)).build()
                    .perform(post("/webui/file-analysis").contentType("application/json").content(body.toJSONString()))
                    .andReturn().getResponse();
            JSONObject result = JSONObject.parseObject(response.getContentAsString(StandardCharsets.UTF_8));
            report.put("httpStatus", response.getStatus()); report.put("primaryRequests", primaryCalls.get());
            report.put("nativeFileRejections", rejections.get()); report.put("thirdPartyCalls", toolCalls.get());
            report.put("analysisText", result.getString("analysisText")); report.put("code", result.getString("code"));
            assertEquals("Analysis failed: " + result.getString("code"), 200, response.getStatus());
            assertTrue("Primary model must actually reject the file before delegation", rejections.get() > 0);
            assertEquals("Only the model tool call may invoke external service", 1, toolCalls.get());
            assertTrue("Returned analysis must contain code found only in PDF", result.getString("analysisText").contains(verification));
            report.put("verifiedFileContents", true); report.put("success", true);
            System.out.println("SIM PASS main model rejected PDF -> invoked tool -> third party analyzed URL -> frontend response contains verified PDF content");
        } finally {
            if (fileUrl != null) storage.delete(userId, fileUrl);
            report.put("primaryRequests", primaryCalls.get()); report.put("nativeFileRejections", rejections.get());
            report.put("thirdPartyCalls", toolCalls.get());
            report.put("testUploadDeleted", true);
            Files.write(Paths.get(settings.getString("resultFile")), report.toJSONString().getBytes(StandardCharsets.UTF_8));
            SecurityContextHolder.clearContext();
        }
    }

    /** Test readiness only. Public tunnel health can change after the launcher probe. */
    private static void waitForSignedFile(FileAnalysisStorage storage, Long userId, String fileUrl) throws Exception {
        for (int attempt = 1; attempt <= 12; attempt++) {
            HttpURLConnection connection = null;
            try {
                FileAnalysisReference file = storage.modelReference(userId, fileUrl);
                connection = (HttpURLConnection) new URL(file.validationUrl).openConnection();
                connection.setRequestMethod("HEAD"); connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
                int status = connection.getResponseCode();
                if (status == 200 && "application/pdf".equals(connection.getContentType())
                        && connection.getContentLengthLong() == file.size) return;
                System.out.println("SIM signed file readiness attempt=" + attempt + " status=" + status);
            } catch (IOException | FileAnalysisException e) {
                System.out.println("SIM signed file readiness attempt=" + attempt + " unavailable");
            } finally { if (connection != null) connection.disconnect(); }
            Thread.sleep(1000);
        }
        fail("Public signed file is not reachable; no model request was made");
    }

    private static byte[] pdf(String verification) throws IOException {
        String content = "BT /F1 24 Tf 50 730 Td (FILE TOOL SIMULATION) Tj 0 -48 Td (ACCESS CODE: " + verification + ") Tj ET\n";
        List<String> objects = Arrays.asList("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                "<< /Length " + content.length() + " >>\nstream\n" + content + "endstream", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(output.size()); output.write(((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
        }
        int xref = output.size();
        output.write(("xref\n0 6\n0000000000 65535 f \n").getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets) output.write(String.format(Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
        output.write(("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }
}
