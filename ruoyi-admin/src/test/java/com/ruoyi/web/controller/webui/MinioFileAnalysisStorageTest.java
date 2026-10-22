package com.ruoyi.web.controller.webui;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.webui.file.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.StreamUtils;
import static org.junit.Assert.*;

/** Exercises the actual Java SDK transport against an isolated S3 fixture. No external calls. */
public class MinioFileAnalysisStorageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private HttpServer s3;
    private ExecutorService threads;
    private FileAnalysisProperties properties;
    private FileAnalysisStorage storage;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> metadata = new ConcurrentHashMap<>();
    private final List<String> deletedVersions = new ArrayList<>();
    private int downloads;
    private static final byte[] ORIGINAL = "原始文件，不提取内容。\u0000\u0001".getBytes(StandardCharsets.UTF_8);

    @Before public void setup() throws Exception {
        s3 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s3.createContext("/", exchange -> {
            String key = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String query = exchange.getRequestURI().getRawQuery();
            if ("GET".equals(method) && query != null && query.contains("list-type=")) {
                byte[] xml = ("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>ai-file</Name><IsTruncated>false</IsTruncated></ListBucketResult>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, xml.length); exchange.getResponseBody().write(xml);
            } else if ("PUT".equals(method)) {
                objects.put(key, StreamUtils.copyToByteArray(exchange.getRequestBody()));
                Map<String, String> headers = new HashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (name.toLowerCase(Locale.ROOT).startsWith("x-amz-meta-") || name.equalsIgnoreCase("Content-Type")) headers.put(name, values.get(0));
                });
                metadata.put(key, headers);
                exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
                exchange.getResponseHeaders().set("x-amz-version-id", "fixture-version");
                exchange.sendResponseHeaders(200, -1);
            } else if ("DELETE".equals(method)) {
                deletedVersions.add(query);
                objects.remove(key); metadata.remove(key); exchange.sendResponseHeaders(204, -1);
            } else if (objects.containsKey(key)) {
                byte[] data = objects.get(key);
                metadata.get(key).forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
                exchange.getResponseHeaders().set("x-amz-version-id", "fixture-version");
                exchange.getResponseHeaders().set("Last-Modified", "Thu, 22 Oct 2026 01:00:00 GMT");
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(data.length));
                exchange.sendResponseHeaders(200, "HEAD".equals(method) ? -1 : data.length);
                if (!"HEAD".equals(method)) { downloads++; exchange.getResponseBody().write(data); }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        threads = Executors.newCachedThreadPool(); s3.setExecutor(threads); s3.start();
        properties = new FileAnalysisProperties(); properties.setStorageType("minio");
        properties.setStoragePath(temporary.newFolder("local").getAbsolutePath());
        properties.getMinio().setEndpoint("http://127.0.0.1:" + s3.getAddress().getPort());
        properties.getMinio().setBucket("ai-file"); properties.getMinio().setPrefix("fixture/");
        properties.getMinio().setAccessKey("fixture-access"); properties.getMinio().setSecretKey("fixture-secret");
        storage = new FileAnalysisStorage(properties);
    }
    @After public void cleanup() { s3.stop(0); threads.shutdownNow(); }
    private String upload() { return storage.upload(11L, new MockMultipartFile("file", "中文.png", "image/png", ORIGINAL)).getString("fileUrl"); }
    private byte[] download(String url, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        try { assertEquals(200, connection.getResponseCode()); return StreamUtils.copyToByteArray(connection.getInputStream()); }
        finally { connection.disconnect(); }
    }

    @Test public void storesOriginalAndMetadataRemotelyAndUsesDistinctGetAndHeadSignatures() throws Exception {
        String url = upload();
        assertTrue(url.startsWith(properties.getMinio().getEndpoint() + "/ai-file/fixture/11/"));
        assertTrue(url.contains("X-Amz-Signature="));
        assertEquals(1, objects.size()); assertArrayEquals(ORIGINAL, objects.values().iterator().next());
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(java.nio.file.Paths.get(properties.getStoragePath()))) { assertEquals(0, files.count()); }
        FileAnalysisReference reference = storage.modelReference(11L, url);
        assertEquals(0, downloads); // Analysis reference construction never reads the file body.
        assertEquals("中文.png", reference.fileName); assertEquals("image/png", reference.mimeType);
        assertNotEquals(reference.url, reference.validationUrl);
        assertTrue(reference.url.contains("X-Amz-Signature="));
        assertFalse(reference.url.contains("fixture-secret"));
        assertArrayEquals(ORIGINAL, download(reference.url, "GET"));
        download(reference.validationUrl, "HEAD");
        assertArrayEquals(ORIGINAL, new FileAnalysisStorage(properties).load(11L, url).getBytes());
    }

    @Test public void ownershipIsEnforcedAndDeletionPurgesOnlyTheUploadedVersion() {
        String url = upload();
        try { storage.load(22L, url); fail(); } catch (FileAnalysisException e) { assertEquals("FILE_NOT_FOUND", e.getCode()); }
        try { storage.delete(22L, url); fail(); } catch (FileAnalysisException e) { assertEquals("FILE_NOT_FOUND", e.getCode()); }
        assertEquals(1, objects.size());
        storage.delete(11L, url); assertEquals(0, objects.size());
        assertTrue(deletedVersions.get(0).contains("versionId=fixture-version"));
        storage.delete(11L, url); // Idempotent, without a new delete marker.
        assertEquals(1, deletedVersions.size());
    }

    @Test public void expiredObjectsCannotBeSignedAndAreRemoved() {
        Clock now = Clock.systemUTC(); properties.setRetentionSeconds(60);
        String url = new FileAnalysisStorage(properties, now).upload(11L, new MockMultipartFile("file", "x.pdf", "application/pdf", ORIGINAL)).getString("fileUrl");
        try { new FileAnalysisStorage(properties, Clock.offset(now, Duration.ofMinutes(2))).modelReference(11L, url); fail(); }
        catch (FileAnalysisException e) { assertEquals("FILE_EXPIRED", e.getCode()); }
        assertEquals(0, objects.size());
    }

    @Test public void previousLocalUploadsRemainPrivateAndDownloadableAfterSwitch() {
        properties.setStorageType("local");
        FileAnalysisStorage local = new FileAnalysisStorage(properties);
        String url = local.upload(11L, new MockMultipartFile("file", "old.txt", "text/plain", ORIGINAL)).getString("fileUrl");
        properties.setStorageType("minio");
        FileAnalysisStorage switched = new FileAnalysisStorage(properties);
        assertArrayEquals(ORIGINAL, switched.load(11L, url).getBytes());
        assertTrue(switched.modelReference(11L, url).url.contains("X-Amz-Signature="));
        assertEquals(1, objects.size());
        assertArrayEquals(ORIGINAL, new FileAnalysisStorage(properties).load(11L, url).getBytes());
        switched.delete(11L, url); assertEquals(0, objects.size());
    }

    @Test public void invalidConfigurationAndArbitraryUrlsAreRejectedWithoutLeakingSecrets() {
        try { storage.modelReference(11L, "http://127.0.0.1/private"); fail(); }
        catch (FileAnalysisException e) { assertEquals("INVALID_FILE_URL", e.getCode()); }
        properties.getMinio().setEndpoint("http://fixture-secret@example.test/path");
        try { new FileAnalysisStorage(properties); fail(); }
        catch (FileAnalysisException e) { assertEquals("INVALID_MINIO_CONFIG", e.getCode()); assertFalse(e.getMessage().contains("fixture-secret")); }
    }

    @Test public void absoluteUrlIsOnlyAnOwnedObjectIdentifierAndItsExpiredSignatureIsRefreshed() {
        String url = upload();
        String stale = url.substring(0, url.indexOf('?')) + "?X-Amz-Date=20000101T000000Z&X-Amz-Signature=old";
        assertTrue(storage.modelReference(11L, stale).url.contains("X-Amz-Signature="));
        assertEquals(0, downloads);
        String path = java.net.URI.create(url).getRawPath();
        for (String attack : Arrays.asList("http://evil.example" + path, url + "#fragment", url.replace("/ai-file/", "/other-bucket/"),
                url.replace("/fixture/", "/other-prefix/"), url.replace("/11/", "/11%2F/"), url.replace("http://", "file://"))) {
            try { storage.modelReference(11L, attack); fail("Untrusted URL was accepted"); }
            catch (FileAnalysisException e) { assertEquals("INVALID_FILE_URL", e.getCode()); }
        }
        assertEquals(0, downloads);
    }
}
