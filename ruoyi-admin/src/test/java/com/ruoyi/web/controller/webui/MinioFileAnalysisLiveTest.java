package com.ruoyi.web.controller.webui;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.webui.domain.vo.AiServiceVo;
import com.ruoyi.webui.domain.vo.SysLoginVo;
import com.ruoyi.webui.file.*;
import com.ruoyi.webui.service.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Collections;
import javax.imageio.ImageIO;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.StreamUtils;
import static org.junit.Assert.*;

/** Explicit live opt-in only. Creates and deletes its own MinIO object and optionally calls the user's model. */
public class MinioFileAnalysisLiveTest {
    @Rule public TemporaryFolder local = new TemporaryFolder();
    @Test public void signedDownloadsAndCurrentUserImageAnalysis() throws Exception {
        Assume.assumeTrue("Explicit live opt-in required", "true".equals(System.getenv("MINIO_LIVE_TEST")));
        FileAnalysisProperties properties = new FileAnalysisProperties();
        properties.setStorageType("minio"); properties.setStoragePath(local.getRoot().getAbsolutePath());
        properties.getMinio().setConfigFile(System.getenv("MINIO_TEST_CONFIG"));
        FileAnalysisStorage storage = new FileAnalysisStorage(properties);
        String code = Integer.toString(new SecureRandom().nextInt(900000) + 100000);
        BufferedImage image = new BufferedImage(720, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 720, 220);
        graphics.setColor(Color.BLACK); graphics.setFont(new Font("SansSerif", Font.BOLD, 90));
        graphics.drawString(code, 100, 145); graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output);
        byte[] original = output.toByteArray();
        String url = null;
        JSONObject report = new JSONObject(); report.put("success", false);
        try {
            url = storage.upload(1L, new MockMultipartFile("file", "minio-image-check.png", "image/png", original)).getString("fileUrl");
            FileAnalysisReference file = storage.modelReference(1L, url);
            report.put("storage", "minio"); report.put("fileEndpoint", new URI(file.url).getScheme() + "://" + new URI(file.url).getAuthority());
            HttpURLConnection head = (HttpURLConnection) new URL(file.validationUrl).openConnection();
            head.setRequestMethod("HEAD"); head.setConnectTimeout(10000); head.setReadTimeout(10000);
            try { assertEquals(200, head.getResponseCode()); assertEquals(original.length, head.getContentLengthLong()); assertEquals("image/png", head.getContentType()); }
            finally { head.disconnect(); }
            report.put("signedHeadVerified", true);
            HttpURLConnection get = (HttpURLConnection) new URL(file.url).openConnection();
            get.setConnectTimeout(10000); get.setReadTimeout(10000);
            try (InputStream input = get.getInputStream()) { assertArrayEquals(original, StreamUtils.copyToByteArray(input)); }
            finally { get.disconnect(); }
            assertArrayEquals(original, storage.load(1L, url).getBytes());
            report.put("originalBytesVerified", true);
            try { storage.modelReference(999L, url); fail("Other users must not access this file"); }
            catch (FileAnalysisException e) { assertEquals("FILE_NOT_FOUND", e.getCode()); }
            report.put("ownershipVerified", true);
            String primaryFile = System.getenv("MINIO_TEST_PRIMARY_CONFIG");
            if (primaryFile != null) {
                JSONObject primary = JSONObject.parseObject(new String(Files.readAllBytes(Paths.get(primaryFile)), StandardCharsets.UTF_8));
                AiServiceVo config = new AiServiceVo(); config.setServiceName(primary.getString("serviceName"));
                config.setServiceUrl(primary.getString("baseUrl")); config.setAiServiceKey(primary.getString("apiKey")); config.setModelName(primary.getString("model"));
                SysLoginVo user = new SysLoginVo(); user.setAiService(config);
                IUserWebuiConfigService configs = (IUserWebuiConfigService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { IUserWebuiConfigService.class }, (proxy, method, args) -> {
                    assertEquals("selectUserConfigByUserId", method.getName()); assertEquals(1L, args[0]); return user;
                });
                AiPromptService prompts = new AiPromptService();
                FileAnalysisService service = new FileAnalysisService(new AiAnalysisService(configs, prompts, 10000, 120000), prompts, properties, Collections.singletonList(new UnconfiguredFileAnalysisTool()));
                FileAnalysisResult result = service.analyze(1L, "minio-live-check", file, "保持原文件，直接读取图片。", "只返回图片中清晰显示的六位数字，请基于实际看到的内容回答。");
                assertTrue("Model must read digits present only in image pixels", result.analysisText.contains(code));
                report.put("model", primary.getString("model")); report.put("modelReadImageVerified", true);
                report.put("analysisText", result.analysisText);
            }
            report.put("success", true);
            System.out.println("MINIO PASS signed GET/HEAD, original bytes, ownership and requested model checks");
        } finally {
            if (url != null) {
                storage.delete(1L, url); report.put("testUploadDeleted", true);
                try { storage.load(1L, url); fail("Deleted object remains accessible"); }
                catch (FileAnalysisException e) { assertEquals("FILE_NOT_FOUND", e.getCode()); }
            }
            String resultFile = System.getenv("MINIO_TEST_RESULT");
            if (resultFile != null) Files.write(Paths.get(resultFile), report.toJSONString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
