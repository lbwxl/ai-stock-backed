package com.ruoyi.web.controller.webui;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.webui.domain.vo.AiServiceVo;
import com.ruoyi.webui.domain.vo.SysLoginVo;
import com.ruoyi.webui.service.AiAnalysisService;
import com.ruoyi.webui.service.AiPromptService;
import com.ruoyi.webui.service.IUserWebuiConfigService;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.junit.Assert.*;

/** Exercises the real service and HTTP transport against a local provider; no database or real keys. */
public class AiAnalysisControllerTest {
    private HttpServer provider;
    private AiAnalysisController controller;
    private IUserWebuiConfigService configs;
    private Map<Long, SysLoginVo> users;
    private JSONObject received;
    private String authorization;
    private String upstreamPath;
    private int providerStatus;
    private String providerBody;
    private AtomicInteger calls;
    private boolean wrongContentType;
    private boolean pauseAfterChunk;
    private boolean timeoutAfterChunk;
    private CountDownLatch chunkForwarded;

    @Before
    public void setup() throws Exception {
        users = new HashMap<>();
        calls = new AtomicInteger();
        providerStatus = 200;
        chunkForwarded = new CountDownLatch(1);
        providerBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"分析结果\"}}]}";
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/", exchange -> {
            calls.incrementAndGet();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            upstreamPath = exchange.getRequestURI().getPath();
            received = JSONObject.parseObject(new String(
                    org.springframework.util.StreamUtils.copyToByteArray(exchange.getRequestBody()), StandardCharsets.UTF_8));
            boolean stream = Boolean.TRUE.equals(received.get("stream"));
            exchange.getResponseHeaders().set("Content-Type", wrongContentType ? "text/html"
                    : stream ? "text/event-stream" : "application/json");
            if (pauseAfterChunk) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try {
                    chunkForwarded.await(2, TimeUnit.SECONDS);
                    if (timeoutAfterChunk) {
                        new CountDownLatch(1).await(250, TimeUnit.MILLISECONDS);
                    }
                    exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
                return;
            }
            byte[] bytes = providerBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(providerStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        provider.start();
        configs = (IUserWebuiConfigService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IUserWebuiConfigService.class }, (proxy, method, args) -> {
                    if ("selectUserConfigByUserId".equals(method.getName())) {
                        return users.get((Long) args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        controller = new AiAnalysisController(new AiAnalysisService(configs, new AiPromptService(), 1000, 1000));
        addUser(11L, "user-one-model", "user-one-provider-key");
        addUser(22L, "user-two-model", "user-two-provider-key");
        login(11L);
    }

    @After
    public void cleanup() {
        SecurityContextHolder.clearContext();
        provider.stop(0);
    }

    private void addUser(Long id, String model, String key) {
        AiServiceVo ai = new AiServiceVo();
        ai.setServiceName("openai-compatible");
        ai.setServiceUrl("http://127.0.0.1:" + provider.getAddress().getPort() + "/v1/");
        ai.setModelName(model);
        ai.setAiServiceKey(key);
        SysLoginVo user = new SysLoginVo();
        user.setAiService(ai);
        users.put(id, user);
    }

    private void login(Long id) {
        LoginUser user = new LoginUser();
        user.setUserId(id);
        SysUser account = new SysUser();
        account.setUserName("fixture-" + id);
        user.setUser(account);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "business-jwt"));
    }

    private JSONObject request() {
        return JSONObject.parseObject("{\"scene\":\"feedback\",\"query\":\"分析这只股票\",\"language\":\"zh\",\"questionCount\":1,\"stream\":false}");
    }

    private MockHttpServletResponse execute(JSONObject request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.complete(request, response);
        return response;
    }

    @Test
    public void identityCredentialsAndInstructionsComeFromBackend() throws Exception {
        JSONObject request = request();
        MockHttpServletResponse response = execute(request);
        assertEquals(200, response.getStatus());
        assertEquals(providerBody, response.getContentAsString());
        assertEquals("user-one-model", received.getString("model"));
        assertEquals("Bearer user-one-provider-key", authorization);
        assertEquals("/v1/chat/completions", upstreamPath);
        assertFalse(received.containsKey("userId"));
        assertFalse(received.containsKey("apiKey"));
        assertFalse(received.containsKey("baseURL"));
        assertEquals(0, received.getIntValue("temperature"));
        assertEquals("system", received.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertTrue(received.getJSONArray("messages").getJSONObject(0).getString("content").startsWith("You are an expert researcher."));
        assertTrue(received.getJSONArray("messages").getJSONObject(1).getString("content").contains("分析这只股票"));
        login(22L);
        execute(request);
        assertEquals("user-two-model", received.getString("model"));
        assertEquals("Bearer user-two-provider-key", authorization);
    }

    @Test
    public void streamsReasoningAndTextWithoutWrappingOrBufferingHeaders() throws Exception {
        JSONObject request = request();
        request.put("stream", true);
        request.put("images", JSON.parseArray("[\"data:image/png;base64,AA==\"]"));
        providerBody = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"结论\"}}]}\n\ndata: [DONE]\n\n";
        MockHttpServletResponse response = execute(request);
        assertEquals(providerBody, response.getContentAsString());
        assertTrue(response.getContentType().startsWith("text/event-stream"));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        assertEquals("data:image/png;base64,AA==", received.getJSONArray("messages").getJSONObject(1)
                .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"));
        assertTrue(response.isCommitted());
    }

    @Test
    public void clientMessagesOrSystemInstructionsCannotBypassBusinessScenes() throws Exception {
        for (String key : new String[] { "messages", "system", "prompt", "model", "temperature" }) {
            JSONObject request = request();
            request.put(key, "override");
            assertEquals(400, execute(request).getStatus());
        }
        assertEquals(0, calls.get());
    }

    @Test
    public void missingConfigurationAndMalformedBusinessDataNeverCallProvider() throws Exception {
        login(33L);
        assertEquals(400, execute(request()).getStatus());
        login(11L);
        users.get(11L).getAiService().setAiServiceKey("");
        assertEquals(400, execute(request()).getStatus());
        assertEquals(400, execute(new JSONObject()).getStatus());
        JSONObject request = request();
        request.put("stream", "true");
        assertEquals(400, execute(request).getStatus());
        assertEquals(0, calls.get());
    }

    @Test
    public void invalidProviderUrlAndRedirectAreRejected() throws Exception {
        AiServiceVo ai = users.get(11L).getAiService();
        String original = ai.getServiceUrl();
        for (String url : new String[] { "file:///tmp/key", "http://name:secret@localhost/v1", original + "?token=secret" }) {
            ai.setServiceUrl(url);
            assertEquals(400, execute(request()).getStatus());
        }
        assertEquals(0, calls.get());
        ai.setServiceUrl(original + "chat/completions");
        providerStatus = 302;
        MockHttpServletResponse redirectResponse = execute(request());
        assertEquals(502, redirectResponse.getStatus());
        assertTrue(redirectResponse.getContentAsString().contains("HTTPS"));
        assertEquals("/v1/chat/completions", upstreamPath);
        assertEquals(1, calls.get());
    }

    @Test
    public void providerFailuresAreSanitizedAndDoNotBecomeLoginFailures() throws Exception {
        providerBody = "user-one-provider-key private prompt";
        for (int status : new int[] { 401, 403, 429, 500 }) {
            providerStatus = status;
            MockHttpServletResponse response = execute(request());
            assertEquals(status == 429 ? 429 : 502, response.getStatus());
            String body = response.getContentAsString();
            assertFalse(body.contains("user-one-provider-key"));
            assertFalse(body.contains("private prompt"));
            assertNotNull(JSONObject.parseObject(body).getJSONObject("error").getString("message"));
        }
    }

    @Test
    public void wrongResponseTypeAndConnectionFailureAreReported() throws Exception {
        wrongContentType = true;
        assertEquals(502, execute(request()).getStatus());
        provider.stop(0);
        assertEquals(502, execute(request()).getStatus());
    }

    @Test
    public void forwardsFirstChunkBeforeProviderCompletes() throws Exception {
        pauseAfterChunk = true;
        JSONObject request = request();
        request.put("stream", true);
        MockHttpServletResponse response = streamingResponse();
        controller.complete(request, response);
        assertEquals(0, chunkForwarded.getCount());
        assertTrue(response.getContentAsString().contains("data: [DONE]"));
    }

    private MockHttpServletResponse streamingResponse() {
        return new MockHttpServletResponse() {
            @Override
            public void flushBuffer() {
                super.flushBuffer();
                chunkForwarded.countDown();
            }
        };
    }

    @Test
    public void timeoutAfterStreamingStartsEmitsSafeSseError() throws Exception {
        pauseAfterChunk = true;
        timeoutAfterChunk = true;
        controller = new AiAnalysisController(new AiAnalysisService(configs, new AiPromptService(), 1000, 80));
        JSONObject request = request();
        request.put("stream", true);
        MockHttpServletResponse response = streamingResponse();
        controller.complete(request, response);
        String body = response.getContentAsString();
        assertTrue(body.contains("first"));
        assertTrue(body.contains("\"error\""));
        assertTrue(body.contains("超时"));
        assertFalse(body.contains("user-one-provider-key"));
    }

    @Test
    public void acceptsRealSpringMvcJsonDeserialization() throws Exception {
        MockHttpServletResponse response = MockMvcBuilders.standaloneSetup(controller).build()
                .perform(MockMvcRequestBuilders.post("/webui/ai/chat/completions")
                        .contentType("application/json").content(request().toJSONString()))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals(providerBody, response.getContentAsString());
        assertEquals(1, calls.get());
    }

    @Test
    public void currentConfigDoesNotExposeAiSecrets() throws Exception {
        UserWebuiConfigController current = new UserWebuiConfigController();
        ReflectionTestUtils.setField(current, "userWebuiConfigService", configs);
        String json = JSONObject.toJSONString(current.current());
        assertFalse(json.contains("user-one-provider-key"));
        assertFalse(json.contains("127.0.0.1"));
        assertTrue(json.contains("user-one-model"));
        assertEquals(200, execute(request()).getStatus());
    }
}
