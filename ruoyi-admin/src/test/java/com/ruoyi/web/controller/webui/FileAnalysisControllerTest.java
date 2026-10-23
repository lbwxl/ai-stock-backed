package com.ruoyi.web.controller.webui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.webui.domain.vo.AiServiceVo;
import com.ruoyi.webui.domain.vo.SysLoginVo;
import com.ruoyi.webui.file.*;
import com.ruoyi.webui.service.AiAnalysisService;
import com.ruoyi.webui.service.AiPromptService;
import com.ruoyi.webui.service.IUserWebuiConfigService;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Real model HTTP transport + multipart controller; all model behavior is a local fixture. */
public class FileAnalysisControllerTest {
    @Rule public TemporaryFolder storageRoot = new TemporaryFolder();
    private FileAnalysisStorage storage;
    private HttpServer provider;
    private java.util.concurrent.ExecutorService providerThreads;
    private boolean fetchFileUrl;
    private int fileStatus = 200;
    private String fileType;
    private boolean failFileAfterModelRequest;
    private boolean wrongFileLength;
    private byte[] downloadedBytes;
    private String downloadAuthorization;
    private final List<JSONObject> requests = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private final List<Long> resolvedUsers = new ArrayList<>();
    private final Queue<String> replies = new ArrayDeque<>();
    private final Queue<Integer> statuses = new ArrayDeque<>();
    private FileAnalysisProperties properties;
    private AiAnalysisService ai;
    private AiPromptService prompts;
    private FileAnalysisService service;
    private int toolCalls;
    private FileAnalysisContext toolContext;
    private String toolTask;
    private static final byte[] ORIGINAL = "秘密原文，不允许在后端提取。\u0000\u0001".getBytes(StandardCharsets.UTF_8);

    @Before
    public void setup() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/", exchange -> {
            requests.add(JSONObject.parseObject(new String(org.springframework.util.StreamUtils.copyToByteArray(
                    exchange.getRequestBody()), StandardCharsets.UTF_8)));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = statuses.isEmpty() ? 200 : statuses.remove();
            if (failFileAfterModelRequest) { fileStatus = 530; }
            String response = replies.isEmpty() ? answer("模型直接分析的结果") : replies.remove();
            if (fetchFileUrl && status == 200) {
                JSONObject part = requests.get(requests.size() - 1).getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1);
                String url = "image_url".equals(part.getString("type")) ? part.getJSONObject("image_url").getString("url") : part.getJSONObject("file").getString("file_data");
                java.net.HttpURLConnection download = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                assertEquals(200, download.getResponseCode());
                downloadedBytes = org.springframework.util.StreamUtils.copyToByteArray(download.getInputStream());
                download.disconnect();
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        provider.createContext(FileAnalysisStorage.MODEL_URL_PREFIX, exchange -> {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            downloadAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            FileAnalysisUpload file = storage.loadForModel(Long.valueOf(parts[4]), parts[5], Long.parseLong(parts[6]), parts[7]);
            byte[] data = file.getBytes();
            exchange.getResponseHeaders().set("Content-Type", fileType == null ? file.mimeType : fileType);
            exchange.getResponseHeaders().set("Content-Length", Long.toString(wrongFileLength ? data.length + 1 : data.length));
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(fileStatus, -1);
            } else {
                exchange.sendResponseHeaders(fileStatus, data.length);
                exchange.getResponseBody().write(data);
            }
            exchange.close();
        });
        providerThreads = java.util.concurrent.Executors.newCachedThreadPool();
        provider.setExecutor(providerThreads);
        provider.start();
        IUserWebuiConfigService configs = (IUserWebuiConfigService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IUserWebuiConfigService.class }, (proxy, method, args) -> {
                    if (!"selectUserConfigByUserId".equals(method.getName())) throw new UnsupportedOperationException();
                    Long id = (Long) args[0];
                    resolvedUsers.add(id);
                    AiServiceVo config = new AiServiceVo();
                    config.setModelName("user-" + id + "-model");
                    config.setAiServiceKey("private-key-" + id);
                    config.setServiceName("openai-compatible");
                    config.setServiceUrl("http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
                    SysLoginVo user = new SysLoginVo();
                    user.setAiService(config);
                    return user;
                });
        prompts = new AiPromptService();
        ai = new AiAnalysisService(configs, prompts, 1000, 1000);
        properties = new FileAnalysisProperties();
        properties.setStoragePath(storageRoot.newFolder("private").getAbsolutePath());
        storage = new FileAnalysisStorage(properties);
        properties.setToolHandler("fixture");
        properties.setPublicBaseUrl("http://127.0.0.1:" + provider.getAddress().getPort());
        service = withTools(new FileAnalysisTool() {
            public String getId() { return "fixture"; }
            public String analyze(FileAnalysisContext file, String task) {
                toolCalls++;
                toolContext = file;
                toolTask = task;
                return "第三方处理结果";
            }
        });
        LoginUser login = new LoginUser();
        login.setUserId(22L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, "jwt"));
    }

    @After
    public void cleanup() { SecurityContextHolder.clearContext(); provider.stop(0); providerThreads.shutdownNow(); }

    private FileAnalysisService withTools(FileAnalysisTool... tools) {
        return new FileAnalysisService(ai, prompts, properties, Arrays.asList(tools));
    }
    private MockMultipartFile file(String name, String mime) { return new MockMultipartFile("file", name, mime, ORIGINAL); }
    private FileAnalysisResult analyze(Long userId, String requestId, org.springframework.web.multipart.MultipartFile file, String processing, String analysis) {
        String url = storage.upload(userId, file).getString("fileUrl");
        return service.analyze(userId, requestId, storage.modelReference(userId, url), processing, analysis);
    }
    private FileAnalysisResult run() { return analyze(11L, "request-fixture", file("data.txt", "text/plain"), "处理要求", "分析要求"); }
    private String answer(String value) {
        JSONObject message = new JSONObject(); message.put("content", value); message.put("role", "assistant");
        return wrap(message);
    }
    private String wrap(JSONObject message) {
        JSONObject choice = new JSONObject(); choice.put("message", message);
        JSONObject response = new JSONObject(); response.put("choices", Arrays.asList(choice)); return response.toJSONString();
    }
    private String tool(String name, String arguments) {
        JSONObject function = new JSONObject(); function.put("name", name); function.put("arguments", arguments);
        JSONObject call = new JSONObject(); call.put("type", "function"); call.put("id", "call-1"); call.put("function", function);
        JSONObject message = new JSONObject(); message.put("role", "assistant"); message.put("content", null);
        message.put("reasoning_content", "需要外部处理"); message.put("tool_calls", Arrays.asList(call)); return wrap(message);
    }
    private String tool() { return tool(FileAnalysisService.TOOL_NAME, "{\"task\":\"请分析原文件\"}"); }
    private void expectFailure(int status, String code) {
        try { run(); fail("Expected " + code); }
        catch (FileAnalysisException e) { assertEquals(status, e.getStatus()); assertEquals(code, e.getCode()); }
    }

    @Test public void sendsOnlySignedUrlToConfiguredModelAndProviderDownloadsOriginalFile() {
        fetchFileUrl = true;
        FileAnalysisResult result = run();
        assertEquals("模型直接分析的结果", result.analysisText);
        assertEquals("data.txt", result.fileName);
        assertEquals("request-fixture", result.requestId);
        assertNull(result.previewFile);
        assertEquals(0, toolCalls);
        JSONObject request = requests.get(0);
        assertEquals("user-11-model", request.getString("model"));
        assertEquals("Bearer private-key-11", authHeaders.get(0));
        JSONArray content = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertFalse(content.getJSONObject(0).getString("text").contains("秘密原文"));
        String data = content.getJSONObject(1).getJSONObject("file").getString("file_data");
        assertTrue(data.startsWith(properties.getPublicBaseUrl() + FileAnalysisStorage.MODEL_URL_PREFIX));
        assertTrue(data.endsWith("/data.txt"));
        assertFalse(request.toJSONString().contains("base64"));
        assertFalse(request.toJSONString().contains(Base64.getEncoder().encodeToString(ORIGINAL)));
        assertArrayEquals(ORIGINAL, downloadedBytes);
        assertNull(downloadAuthorization);
        assertEquals("auto", request.getString("tool_choice"));
        assertFalse(request.getBooleanValue("stream"));
        assertEquals(1, request.getJSONArray("tools").size());
    }

    @Test public void brokenTunnelIsReportedBeforeAnyModelOrToolCall() {
        fileStatus = 530;
        expectFailure(503, "FILE_URL_UNREACHABLE");
        assertEquals(0, requests.size()); assertEquals(0, toolCalls);
    }

    @Test public void signedHeadValidationUrlIsSeparateFromTheGetUrlSentToTheModel() {
        provider.createContext("/signed-head", exchange -> {
            assertEquals("HEAD", exchange.getRequestMethod());
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Content-Length", "123");
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        String base = "http://127.0.0.1:" + provider.getAddress().getPort();
        FileAnalysisReference file = new FileAnalysisReference("image.png", "image/png", 123, base + "/signed-get", base + "/signed-head");
        assertEquals("模型直接分析的结果", service.analyze(11L, "request", file, "处理", "分析").analysisText);
        JSONObject image = requests.get(0).getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1);
        assertEquals(base + "/signed-get", image.getJSONObject("image_url").getString("url"));
        assertFalse(requests.get(0).toJSONString().contains("signed-head"));
    }

    @Test public void htmlWarningOrWrongLengthIsNotPassedOffAsAnUnsupportedImage() {
        fileType = "text/html";
        expectFailure(503, "FILE_URL_UNREACHABLE");
        fileType = null; wrongFileLength = true;
        expectFailure(503, "FILE_URL_UNREACHABLE");
        assertEquals(0, requests.size()); assertEquals(0, toolCalls);
    }

    @Test public void tunnelFailureDuringProviderRequestNeverFallsBackToExternalTool() {
        failFileAfterModelRequest = true; statuses.add(400); replies.add("{}");
        expectFailure(503, "FILE_URL_UNREACHABLE");
        assertEquals(1, requests.size()); assertEquals(0, toolCalls);
    }

    @Test public void imagesAndPdfAreRawAttachmentsNotLocalProcessingRoutes() {
        analyze(11L, "image", file("image.png", "image/png"), "处理", "分析");
        JSONObject image = requests.get(0).getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1);
        assertEquals("image_url", image.getString("type"));
        assertTrue(image.getJSONObject("image_url").getString("url").endsWith("/image.png"));
        analyze(11L, "pdf", file("file.pdf", "application/pdf"), "处理", "分析");
        JSONObject pdf = requests.get(1).getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1);
        assertEquals("file", pdf.getString("type"));
        assertTrue(pdf.getJSONObject("file").getString("file_data").endsWith("/file.pdf"));
        assertEquals(0, toolCalls);
    }

    @Test public void onlyModelToolCallInvokesHookThenSameModelSummarizes() {
        replies.add(tool()); replies.add(answer("结合工具的最终结论"));
        assertEquals("结合工具的最终结论", run().analysisText);
        assertEquals(1, toolCalls);
        assertEquals(Long.valueOf(11), toolContext.getUserId());
        assertEquals("请分析原文件", toolTask);
        assertTrue(toolContext.getFileUrl().startsWith(properties.getPublicBaseUrl() + FileAnalysisStorage.MODEL_URL_PREFIX));
        assertEquals(ORIGINAL.length, toolContext.getFileSize());
        assertEquals(Arrays.asList(11L, 11L), resolvedUsers);
        JSONArray messages = requests.get(1).getJSONArray("messages");
        assertEquals("需要外部处理", messages.getJSONObject(2).getString("reasoning_content"));
        assertEquals("call-1", messages.getJSONObject(3).getString("tool_call_id"));
        assertEquals("第三方处理结果", messages.getJSONObject(3).getString("content"));
    }

    @Test public void persistedTaskWorkflowRequestsManualMaintenanceWithoutExecutingExternalTool() {
        String url = storage.upload(11L, file("data.pdf", "application/pdf")).getString("fileUrl");
        replies.add(tool());
        try { service.analyzeForTask(11L, "task-fixture", storage.modelReference(11L, url), "处理要求", "分析要求"); fail(); }
        catch (FileAnalysisException e) { assertEquals("FILE_MANUAL_REVIEW_REQUIRED", e.getCode()); }
        assertEquals(0, toolCalls);
    }

    @Test public void filesAttachedToRecordsOutliveTemporaryRetentionAndCannotBeRemovedAsTemporaryUploads() {
        Clock now = Clock.systemUTC();
        FileAnalysisStorage initial = new FileAnalysisStorage(properties, now);
        String url = initial.upload(11L, file("data.txt", "text/plain")).getString("fileUrl");
        initial.retainForRecord(11L, url);
        FileAnalysisStorage later = new FileAnalysisStorage(properties, Clock.offset(now, java.time.Duration.ofDays(2)));
        assertArrayEquals(ORIGINAL, later.load(11L, url).getBytes());
        try { later.delete(11L, url); fail(); } catch (FileAnalysisException e) { assertEquals("FILE_IN_USE", e.getCode()); }
    }

    @Test public void fullLocalSignedUrlsCanBeMaintainedOnlyByTheirOwnerAndConfiguredHost() {
        String identifier = storage.upload(11L, file("data.txt", "text/plain")).getString("fileUrl");
        String fullUrl = storage.modelReference(11L, identifier).url;
        assertEquals(identifier.substring(FileAnalysisStorage.URL_PREFIX.length()), storage.fileId(11L, fullUrl));
        storage.retainForRecord(11L, fullUrl);
        assertArrayEquals(ORIGINAL, storage.load(11L, fullUrl).getBytes());
        try { storage.fileId(22L, fullUrl); fail(); } catch (FileAnalysisException e) { assertEquals("INVALID_FILE_URL", e.getCode()); }
        try { storage.fileId(11L, fullUrl.replace("127.0.0.1", "attacker.example")); fail(); } catch (FileAnalysisException e) { assertEquals("INVALID_FILE_URL", e.getCode()); }
    }

    @Test public void attachmentRejectionIsSentToSameModelBeforeAnyToolRuns() {
        statuses.add(400); statuses.add(200); statuses.add(200);
        replies.add("{}"); replies.add(tool()); replies.add(answer("外部服务完成后的结论"));
        assertEquals("外部服务完成后的结论", run().analysisText);
        assertEquals(1, toolCalls);
        assertEquals(Arrays.asList(11L, 11L, 11L), resolvedUsers);
        String metadata = requests.get(1).getJSONArray("messages").getJSONObject(1).getString("content");
        assertTrue(metadata.contains("HTTP 400"));
        assertTrue(metadata.contains("未提供文件内容"));
        assertFalse(requests.get(1).toJSONString().contains(Base64.getEncoder().encodeToString(ORIGINAL)));
        assertFalse(requests.get(1).toJSONString().contains("秘密原文"));
    }

    @Test public void cannotReturnFabricatedAnalysisWithoutFileOrTool() {
        statuses.add(415); statuses.add(200); replies.add("{}"); replies.add(answer("根据文件名猜测的结论"));
        expectFailure(422, "MODEL_CANNOT_READ_FILE"); assertEquals(0, toolCalls);
    }

    @Test public void unconfiguredHookFailsOnlyAfterModelChoosesIt() {
        properties.setToolHandler("unconfigured"); service = withTools(new UnconfiguredFileAnalysisTool());
        assertEquals("模型直接分析的结果", run().analysisText);
        replies.add(tool()); expectFailure(503, "FILE_TOOL_NOT_CONFIGURED"); assertEquals(2, requests.size());
    }

    @Test public void providerAuthFailureDoesNotInvalidateBusinessLoginOrInvokeHook() {
        statuses.add(401); expectFailure(502, "MODEL_AUTH_FAILED");
        assertEquals(1, requests.size()); assertEquals(0, toolCalls);
    }

    @Test public void providerRateLimitDoesNotInvokeHook() {
        statuses.add(429); expectFailure(429, "MODEL_RATE_LIMITED"); assertEquals(0, toolCalls);
    }

    @Test public void unknownToolsAndExtraToolArgumentsCannotExecute() {
        replies.add(tool("read_arbitrary_url", "{\"task\":\"x\"}")); expectFailure(502, "INVALID_TOOL_CALL");
        replies.add(tool(FileAnalysisService.TOOL_NAME, "{\"task\":\"x\",\"url\":\"http://invalid\"}"));
        expectFailure(502, "INVALID_TOOL_CALL"); assertEquals(0, toolCalls);
    }

    @Test public void limitsToolRounds() {
        properties.setMaxToolRounds(1); replies.add(tool()); replies.add(tool());
        expectFailure(502, "TOOL_LIMIT_REACHED"); assertEquals(1, toolCalls); assertEquals(2, requests.size());
    }

    @Test public void neverRestartsCompletedToolAfterLaterRequestRejection() {
        replies.add(tool()); replies.add("{}"); statuses.add(200); statuses.add(400);
        expectFailure(502, "MODEL_REQUEST_REJECTED"); assertEquals(1, toolCalls); assertEquals(2, requests.size());
    }

    @Test public void malformedOrTruncatedResultsAreNotSuccessful() {
        replies.add("{}"); expectFailure(502, "INVALID_MODEL_RESPONSE");
        replies.add("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"半句话\"}}]}");
        expectFailure(502, "ANALYSIS_INCOMPLETE");
    }

    @Test public void validatesUploadBeforeAnyModelRequest() {
        properties.setMaxFileSize(1); expectFailure(413, "FILE_TOO_LARGE");
        properties.setMaxFileSize(1024);
        try { analyze(11L, "empty", file("x", "text/plain"), "", "分析"); fail(); }
        catch (FileAnalysisException e) { assertEquals("INVALID_REQUEST", e.getCode()); }
        try { analyze(11L, "empty", new MockMultipartFile("file", new byte[0]), "处理", "分析"); fail(); }
        catch (FileAnalysisException e) { assertEquals("INVALID_REQUEST", e.getCode()); }
        assertEquals(0, requests.size());
    }

    private MockMvc mvc() { return MockMvcBuilders.standaloneSetup(new FileAnalysisController(service, storage)).build(); }
    private String uploadedUrl(MockMvc mvc) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(multipart("/webui/file-analysis/files")
                .file(file("data.txt", "text/plain"))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals(0, requests.size()); // Upload must not invoke any model.
        return JSONObject.parseObject(response.getContentAsString(StandardCharsets.UTF_8)).getString("fileUrl");
    }
    private String analysisBody(String url) {
        JSONObject body = new JSONObject(); body.put("fileUrl", url);
        body.put("processingRequirement", "处理"); body.put("analysisRequirement", "分析"); return body.toJSONString();
    }

    @Test public void uploadThenJsonAnalysisUsesAuthenticatedIdentity() throws Exception {
        MockMvc mvc = mvc();
        String url = uploadedUrl(mvc);
        org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(post("/webui/file-analysis")
                .queryParam("userId", "999").queryParam("model", "attacker-model")
                .contentType("application/json").content(analysisBody(url))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals(Arrays.asList(22L), resolvedUsers);
        assertEquals("user-22-model", requests.get(0).getString("model"));
        assertEquals("模型直接分析的结果", JSONObject.parseObject(response.getContentAsString(StandardCharsets.UTF_8)).getString("analysisText"));
    }

    @Test public void validatesJsonAndReportsUnconfiguredTool() throws Exception {
        MockMvc mvc = mvc();
        assertEquals(400, mvc.perform(post("/webui/file-analysis").contentType("application/json").content("{}"))
                .andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(multipart("/webui/file-analysis/files")).andReturn().getResponse().getStatus());
        String url = uploadedUrl(mvc);
        properties.setToolHandler("missing"); replies.add(tool());
        org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(post("/webui/file-analysis")
                .contentType("application/json").content(analysisBody(url))).andReturn().getResponse();
        assertEquals(503, response.getStatus());
        JSONObject body = JSONObject.parseObject(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("FILE_TOOL_NOT_CONFIGURED", body.getString("code"));
        assertNotNull(body.getString("requestId"));
    }

    @Test public void ownerCanDownloadAndDeleteOriginalButOtherUsersCannotReadIt() throws Exception {
        MockMvc mvc = mvc(); String url = uploadedUrl(mvc);
        org.springframework.mock.web.MockHttpServletResponse download = mvc.perform(get(url)).andReturn().getResponse();
        assertEquals(200, download.getStatus()); assertArrayEquals(ORIGINAL, download.getContentAsByteArray());
        assertEquals("private, no-store", download.getHeader("Cache-Control"));
        try { storage.load(999L, url); fail(); } catch (FileAnalysisException e) { assertEquals(404, e.getStatus()); }
        // URL knowledge alone grants no access; a different identity also cannot analyze this upload.
        LoginUser other = new LoginUser(); other.setUserId(999L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(other, "other-jwt"));
        assertEquals(404, mvc.perform(get(url)).andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(post("/webui/file-analysis").contentType("application/json").content(analysisBody(url)))
                .andReturn().getResponse().getStatus());
        assertEquals(0, requests.size());
        storage.delete(22L, url);
        try { storage.load(22L, url); fail(); } catch (FileAnalysisException e) { assertEquals(404, e.getStatus()); }
    }

    @Test public void storageSurvivesServiceRestartAndExpiresWithoutParsingContents() {
        Clock now = Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneOffset.UTC);
        FileAnalysisStorage first = new FileAnalysisStorage(properties, now);
        String url = first.upload(11L, file("data.txt", "text/plain")).getString("fileUrl");
        FileAnalysisStorage restarted = new FileAnalysisStorage(properties, now);
        assertArrayEquals(ORIGINAL, restarted.load(11L, url).getBytes());
        FileAnalysisStorage later = new FileAnalysisStorage(properties, Clock.offset(now, java.time.Duration.ofDays(2)));
        try { later.load(11L, url); fail(); } catch (FileAnalysisException e) { assertEquals("FILE_EXPIRED", e.getCode()); }
    }

    @Test public void arbitraryRemoteUrlsPathsAndUrlTricksAreRejectedWithoutFetching() throws Exception {
        MockMvc mvc = mvc();
        for (String url : Arrays.asList("http://127.0.0.1:3306/", "https://example.com/file.pdf", "file:///etc/passwd",
                "/webui/file-analysis/files/../data", "/webui/file-analysis/files/00000000-0000-0000-0000-000000000000?userId=1")) {
            org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(post("/webui/file-analysis")
                    .contentType("application/json").content(analysisBody(url))).andReturn().getResponse();
            assertEquals(400, response.getStatus());
            assertEquals("INVALID_FILE_URL", JSONObject.parseObject(response.getContentAsString(StandardCharsets.UTF_8)).getString("code"));
        }
        assertEquals(0, requests.size());
    }

    @Test public void signedDownloadWorksWithoutLoginAndRejectsTamperingAndDeletion() throws Exception {
        MockMvc mvc = mvc(); String url = uploadedUrl(mvc);
        FileAnalysisReference ref = storage.modelReference(22L, url);
        String path = java.net.URI.create(ref.url).getRawPath();
        SecurityContextHolder.clearContext();
        org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertArrayEquals(ORIGINAL, response.getContentAsByteArray());
        assertEquals("text/plain", response.getContentType());
        String[] parts = path.split("/");
        parts[7] = (parts[7].startsWith("A") ? "B" : "A") + parts[7].substring(1);
        assertEquals(403, mvc.perform(get(String.join("/", parts))).andReturn().getResponse().getStatus());
        parts = path.split("/"); parts[6] = Long.toString(Long.parseLong(parts[6]) + 1);
        assertEquals(403, mvc.perform(get(String.join("/", parts))).andReturn().getResponse().getStatus());
        parts = path.split("/"); parts[4] = "999";
        assertEquals(403, mvc.perform(get(String.join("/", parts))).andReturn().getResponse().getStatus());
        storage.delete(22L, url);
        assertEquals(403, mvc.perform(get(path)).andReturn().getResponse().getStatus());
    }

    @Test public void signedLinksExpireAndRemainValidAcrossStorageRestart() {
        Clock now = Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneOffset.UTC);
        FileAnalysisStorage first = new FileAnalysisStorage(properties, now);
        String url = first.upload(11L, file("data.txt", "text/plain")).getString("fileUrl");
        String[] parts = java.net.URI.create(first.modelReference(11L, url).url).getPath().split("/");
        FileAnalysisStorage restarted = new FileAnalysisStorage(properties, now);
        assertArrayEquals(ORIGINAL, restarted.loadForModel(11L, parts[5], Long.parseLong(parts[6]), parts[7]).getBytes());
        FileAnalysisStorage later = new FileAnalysisStorage(properties, Clock.offset(now, java.time.Duration.ofHours(2)));
        try { later.loadForModel(11L, parts[5], Long.parseLong(parts[6]), parts[7]); fail(); }
        catch (FileAnalysisException e) { assertEquals("FILE_URL_EXPIRED", e.getCode()); }
    }

    @Test public void missingPublicAddressFailsExplicitlyWithoutEmbeddingBytes() {
        properties.setPublicBaseUrl("");
        properties.setPublicBaseUrlFile(storageRoot.getRoot().toPath().resolve("absent-url").toString());
        expectFailure(503, "FILE_PUBLIC_URL_NOT_CONFIGURED");
        assertEquals(0, requests.size());
    }

    @Test public void jsonCannotOverrideIdentityModelOrSubmitBinaryMessages() throws Exception {
        MockMvc mvc = mvc(); String url = uploadedUrl(mvc);
        JSONObject body = JSONObject.parseObject(analysisBody(url)); body.put("userId", 999);
        assertEquals(400, mvc.perform(post("/webui/file-analysis").contentType("application/json").content(body.toJSONString()))
                .andReturn().getResponse().getStatus());
        assertEquals(415, mvc.perform(multipart("/webui/file-analysis").file(file("data.txt", "text/plain")))
                .andReturn().getResponse().getStatus());
        assertEquals(0, requests.size());
    }

    @Test public void processingStagesShareRequestIdWithoutLoggingCredentialsUrlsOrContent() throws Exception {
        ch.qos.logback.classic.Logger serviceLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FileAnalysisService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); }
        };
        appender.start(); serviceLogger.addAppender(appender);
        try {
            MockMvc mvc = mvc(); String url = uploadedUrl(mvc);
            org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(post("/webui/file-analysis")
                    .contentType("application/json").content(analysisBody(url))).andReturn().getResponse();
            JSONObject body = JSONObject.parseObject(response.getContentAsString(StandardCharsets.UTF_8));
            String requestId = body.getString("requestId");
            assertEquals(requestId, response.getHeader("X-Request-Id"));
            StringBuilder logged = new StringBuilder();
            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                assertEquals(requestId, event.getMDCPropertyMap().get("fileAnalysisRequestId"));
                logged.append(event.getFormattedMessage());
            }
            assertTrue(logged.toString().contains("stage=file_url_checked"));
            assertTrue(logged.toString().contains("stage=model_response"));
            assertTrue(logged.toString().contains("stage=analysis_completed"));
            assertFalse(logged.toString().contains("private-key-22"));
            assertFalse(logged.toString().contains("秘密原文"));
            assertFalse(logged.toString().contains("http://"));
            assertNull(org.slf4j.MDC.get("fileAnalysisRequestId"));
        } finally { serviceLogger.detachAppender(appender); appender.stop(); }
    }
}
