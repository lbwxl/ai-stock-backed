package com.ruoyi.webui.file;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.config.RuoYiConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.UUID;
import java.net.URI;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.web.util.UriUtils;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Private, owner-scoped temporary storage. URLs are local resource identifiers, never arbitrary fetch targets. */
@Service
public class FileAnalysisStorage {
    public static final String URL_PREFIX = "/webui/file-analysis/files/";
    public static final String MODEL_URL_PREFIX = "/webui/file-analysis/model-files/";
    private static final String ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final FileAnalysisProperties properties;
    private final Clock clock;
    private final MinioFileAnalysisStorage minio;
    private final AtomicLong lastCleanup = new AtomicLong();

    @Autowired
    public FileAnalysisStorage(FileAnalysisProperties properties) { this(properties, Clock.systemUTC()); }
    public FileAnalysisStorage(FileAnalysisProperties properties, Clock clock) {
        this.properties = properties; this.clock = clock;
        if (!java.util.Arrays.asList("local", "minio").contains(properties.getStorageType())) {
            throw new FileAnalysisException(500, "INVALID_STORAGE_CONFIG", "不支持的文件存储类型");
        }
        this.minio = "minio".equals(properties.getStorageType()) ? new MinioFileAnalysisStorage(properties, clock) : null;
        org.slf4j.LoggerFactory.getLogger(FileAnalysisStorage.class).info("File analysis storage backend: {}", properties.getStorageType());
    }

    public JSONObject upload(Long userId, MultipartFile file) {
        if (minio != null) return minio.upload(userId, file);
        FileAnalysisUpload upload = FileAnalysisUpload.read(file, properties.getMaxFileSize());
        String id = UUID.randomUUID().toString();
        Path directory = directory(userId, id);
        long expiresAt = clock.millis() + Math.max(60, Math.min(604800, properties.getRetentionSeconds())) * 1000L;
        JSONObject metadata = new JSONObject();
        metadata.put("fileName", upload.fileName);
        metadata.put("mimeType", upload.mimeType);
        metadata.put("size", upload.getSize());
        metadata.put("expiresAt", expiresAt);
        try {
            Files.createDirectories(directory);
            // This local runtime is macOS/Linux; keep private uploads owner-readable only.
            try { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
            catch (UnsupportedOperationException ignored) { }
            Files.write(directory.resolve("data"), upload.getBytes(), StandardOpenOption.CREATE_NEW);
            Files.write(directory.resolve("metadata.json"), metadata.toJSONString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            removeDirectory(directory);
            throw new FileAnalysisException(500, "UPLOAD_FAILED", "文件保存失败，请稍后重试");
        }
        cleanupExpired();
        metadata.put("fileUrl", URL_PREFIX + id);
        return metadata;
    }

    private JSONObject describe(Long userId, String fileUrl) {
        Path directory = directory(userId, idFromUrl(userId, fileUrl));
        try {
            Path meta = directory.resolve("metadata.json");
            Path data = directory.resolve("data");
            if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS)) { throw missing(); }
            if (Files.size(meta) > 4096) { throw missing(); }
            JSONObject metadata = JSONObject.parseObject(new String(Files.readAllBytes(meta), StandardCharsets.UTF_8));
            if (metadata.getLongValue("expiresAt") <= clock.millis()) {
                removeDirectory(directory);
                throw new FileAnalysisException(410, "FILE_EXPIRED", "上传文件已过期，请重新上传");
            }
            long size = Files.size(data);
            if (size > Math.max(1, properties.getMaxFileSize())) { throw new FileAnalysisException(413, "FILE_TOO_LARGE", "文件超过服务器允许的大小"); }
            if (size <= 0 || size != metadata.getLongValue("size")) { throw missing(); }
            return metadata;
        } catch (IOException e) { throw missing(); }
    }

    public FileAnalysisUpload load(Long userId, String fileUrl) {
        if (remote(userId, fileUrl)) return minio.load(userId, idFromUrl(userId, fileUrl));
        JSONObject metadata = describe(userId, fileUrl);
        try {
            return new FileAnalysisUpload(metadata.getString("fileName"), metadata.getString("mimeType"),
                    Files.readAllBytes(directory(userId, idFromUrl(userId, fileUrl)).resolve("data")));
        } catch (IOException e) { throw missing(); }
    }

    /** Analysis reads metadata only. The provider fetches original bytes from this scoped URL. */
    public FileAnalysisReference modelReference(Long userId, String fileUrl) {
        if (remote(userId, fileUrl)) return minio.modelReference(userId, idFromUrl(userId, fileUrl));
        if (minio != null) return migrateLocal(userId, fileUrl);
        JSONObject metadata = describe(userId, fileUrl);
        String id = idFromUrl(userId, fileUrl);
        long expires = Math.min(metadata.getLongValue("expiresAt") / 1000,
                clock.millis() / 1000 + Math.max(60, Math.min(3600, properties.getModelUrlTtlSeconds())));
        String base = publicBaseUrl();
        Path key = directory(userId, id).resolve("model-key");
        try {
            if (!Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
                byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
                try { Files.write(key, random, StandardOpenOption.CREATE_NEW); }
                catch (FileAlreadyExistsException ignored) { }
            }
            String signature = signature(key, userId, id, expires);
            String url = base + MODEL_URL_PREFIX + userId + "/" + id + "/" + expires + "/" + signature + "/"
                    + UriUtils.encodePathSegment(metadata.getString("fileName"), StandardCharsets.UTF_8);
            return new FileAnalysisReference(metadata.getString("fileName"), metadata.getString("mimeType"),
                    metadata.getLongValue("size"), url);
        } catch (IOException e) { throw new FileAnalysisException(500, "FILE_URL_FAILED", "无法生成文件临时访问链接"); }
    }

    /** No login JWT required: the signature grants only this file until expiry. */
    public FileAnalysisUpload loadForModel(Long userId, String id, long expires, String suppliedSignature) {
        if (id == null || !id.matches(ID) || suppliedSignature == null || !suppliedSignature.matches("[A-Za-z0-9_-]{43}")) { throw denied(); }
        if (expires <= clock.millis() / 1000) { throw new FileAnalysisException(410, "FILE_URL_EXPIRED", "文件访问链接已过期"); }
        Path key = directory(userId, id).resolve("model-key");
        try {
            String expected = signature(key, userId, id, expires);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), suppliedSignature.getBytes(StandardCharsets.US_ASCII))) { throw denied(); }
        } catch (IOException e) { throw denied(); }
        return load(userId, URL_PREFIX + id);
    }

    private String signature(Path key, Long userId, String id, long expires) throws IOException {
        if (!Files.isRegularFile(key, LinkOption.NOFOLLOW_LINKS) || Files.size(key) != 32) { throw new IOException("Invalid link key"); }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Files.readAllBytes(key), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((userId + "/" + id + "/" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("HMAC unavailable", e); }
    }

    private String publicBaseUrl() {
        String value = properties.getPublicBaseUrl();
        if ((value == null || value.trim().isEmpty()) && properties.getPublicBaseUrlFile() != null) {
            try {
                Path file = Paths.get(properties.getPublicBaseUrlFile());
                if (Files.isRegularFile(file) && Files.size(file) < 2048) { value = new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
            } catch (IOException ignored) { }
        }
        if (value == null || value.trim().isEmpty()) {
            if (properties.getPublicBaseUrlFile() != null) {
                Path status = Paths.get(properties.getPublicBaseUrlFile()).toAbsolutePath().resolveSibling("file-tunnel-status.json");
                try {
                    if (Files.isRegularFile(status) && Files.size(status) <= 4096) {
                        JSONObject state = JSONObject.parseObject(new String(Files.readAllBytes(status), StandardCharsets.UTF_8));
                        if (java.util.Arrays.asList("starting", "recovering").contains(state.getString("state"))) {
                            throw new FileAnalysisException(503, "FILE_URL_UNREACHABLE", "临时文件隧道正在恢复，请稍后重试；无需重新上传文件");
                        }
                    }
                } catch (IOException ignored) { }
            }
            throw new FileAnalysisException(503, "FILE_PUBLIC_URL_NOT_CONFIGURED", "请先配置模型可访问的文件域名或启动本地文件隧道");
        }
        try {
            URI uri = URI.create(value.trim().replaceAll("/+$", ""));
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) { throw new IllegalArgumentException(); }
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) { throw new FileAnalysisException(500, "INVALID_FILE_PUBLIC_URL", "文件公网地址配置不正确，请联系管理员"); }
    }

    private FileAnalysisException denied() { return new FileAnalysisException(403, "INVALID_FILE_SIGNATURE", "文件访问链接无效"); }

    public void delete(Long userId, String fileUrl) {
        if (minio != null) minio.delete(userId, idFromUrl(userId, fileUrl));
        removeDirectory(directory(userId, idFromUrl(userId, fileUrl)));
    }

    private synchronized FileAnalysisReference migrateLocal(Long userId, String fileUrl) {
        if (remote(userId, fileUrl)) return minio.modelReference(userId, idFromUrl(userId, fileUrl));
        JSONObject metadata = describe(userId, fileUrl);
        FileAnalysisReference reference = minio.importLocal(userId, idFromUrl(userId, fileUrl), load(userId, fileUrl), metadata.getLongValue("expiresAt"));
        // Copy only original bytes and metadata. Remove the local copy after remote verification.
        removeDirectory(directory(userId, idFromUrl(userId, fileUrl)));
        return reference;
    }

    private boolean remote(Long userId, String fileUrl) {
        Path local = directory(userId, idFromUrl(userId, fileUrl));
        // Preserve owner-scoped access to files uploaded before switching the storage backend.
        return minio != null && !Files.isRegularFile(local.resolve("metadata.json"), LinkOption.NOFOLLOW_LINKS);
    }

    private String idFromUrl(Long userId, String url) {
        if (minio != null && url != null && !url.startsWith(URL_PREFIX)) return minio.idFromUrl(userId, url);
        if (url == null || !url.matches(java.util.regex.Pattern.quote(URL_PREFIX) + ID)) {
            throw new FileAnalysisException(400, "INVALID_FILE_URL", "请使用当前系统上传接口返回的文件 URL");
        }
        return url.substring(URL_PREFIX.length());
    }

    private Path directory(Long userId, String id) {
        if (userId == null || userId <= 0) { throw new FileAnalysisException(401, "UNAUTHORIZED", "请先登录"); }
        Path root = Paths.get(properties.getStoragePath()).toAbsolutePath().normalize();
        String profile = RuoYiConfig.getProfile();
        if (profile != null && root.startsWith(Paths.get(profile).toAbsolutePath().normalize())) {
            throw new FileAnalysisException(500, "INVALID_STORAGE_CONFIG", "文件存储配置不正确，请联系管理员");
        }
        return root.resolve(userId.toString()).resolve(id);
    }

    /** Expired uploads are removed at most once per minute, triggered by a successful upload. */
    private void cleanupExpired() {
        long now = clock.millis();
        long last = lastCleanup.get();
        if (now - last < 60000 || !lastCleanup.compareAndSet(last, now)) { return; }
        Path root = Paths.get(properties.getStoragePath()).toAbsolutePath().normalize();
        try (Stream<Path> users = Files.list(root)) {
            users.filter(p -> p.getFileName().toString().matches("[0-9]+") && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .forEach(user -> {
                    try (Stream<Path> files = Files.list(user)) {
                        files.filter(p -> p.getFileName().toString().matches(ID) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                            .forEach(dir -> {
                                try {
                                    Path meta = dir.resolve("metadata.json");
                                    if (Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS) && Files.size(meta) <= 4096) {
                                        JSONObject value = JSONObject.parseObject(new String(Files.readAllBytes(meta), StandardCharsets.UTF_8));
                                        if (value.getLongValue("expiresAt") <= now) { removeDirectory(dir); }
                                    }
                                } catch (IOException | RuntimeException ignored) { /* A broken entry must not fail a new upload. */ }
                            });
                    } catch (IOException ignored) { }
                });
        } catch (IOException ignored) { }
    }

    private void removeDirectory(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve("data"));
            Files.deleteIfExists(directory.resolve("metadata.json"));
            Files.deleteIfExists(directory.resolve("model-key"));
            Files.deleteIfExists(directory);
        } catch (IOException e) { /* Retry via expiry cleanup; do not turn analysis failures into data exposure. */ }
    }
    private FileAnalysisException missing() { return new FileAnalysisException(404, "FILE_NOT_FOUND", "文件不存在或不属于当前用户，请重新上传"); }
}
