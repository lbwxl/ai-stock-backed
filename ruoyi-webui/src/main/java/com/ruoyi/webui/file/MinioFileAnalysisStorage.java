package com.ruoyi.webui.file;

import com.alibaba.fastjson2.JSONObject;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;
import org.springframework.web.multipart.MultipartFile;

/** Owner-scoped S3 objects. File metadata is stored with the object, without local file copies. */
final class MinioFileAnalysisStorage {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MinioFileAnalysisStorage.class);
    private final FileAnalysisProperties properties;
    private final Clock clock;
    private final MinioClient client;
    private final MinioClient publicClient;
    private final String bucket;
    private final String prefix;
    private final URI publicEndpoint;
    private final AtomicLong lastCleanup = new AtomicLong();

    MinioFileAnalysisStorage(FileAnalysisProperties properties, Clock clock) {
        this.properties = properties; this.clock = clock;
        try {
            FileAnalysisProperties.Minio settings = properties.getMinio();
            JSONObject file = new JSONObject();
            if (!blank(settings.getConfigFile())) {
                Path path = Paths.get(settings.getConfigFile());
                if (Files.size(path) > 16384) throw new IllegalArgumentException();
                file = JSONObject.parseObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
            String endpoint = endpoint(value(settings.getEndpoint(), file, "endpoint"));
            String external = value(settings.getPublicEndpoint(), file, "publicEndpoint");
            if (blank(external)) external = endpoint;
            external = endpoint(external);
            publicEndpoint = URI.create(external);
            String access = value(settings.getAccessKey(), file, "accessKey");
            String secret = value(settings.getSecretKey(), file, "secretKey");
            bucket = value(settings.getBucket(), file, "bucket");
            if (blank(access) || blank(secret) || blank(bucket)) throw new IllegalArgumentException();
            String configuredPrefix = settings.getPrefix();
            if (configuredPrefix == null || !configuredPrefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?")) throw new IllegalArgumentException();
            prefix = configuredPrefix.replaceAll("/+$", "") + "/";
            OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(false).followSslRedirects(false).build();
            client = MinioClient.builder().endpoint(endpoint).credentials(access, secret)
                    .region(settings.getRegion()).httpClient(http).build();
            publicClient = external.equals(endpoint) ? client : MinioClient.builder().endpoint(external)
                    .credentials(access, secret).region(settings.getRegion()).httpClient(http).build();
        } catch (Exception e) {
            // Never expose credentials, config contents or a provider exception in API responses.
            throw new FileAnalysisException(500, "INVALID_MINIO_CONFIG", "MinIO 文件存储配置不正确，请联系管理员");
        }
    }

    JSONObject upload(Long userId, MultipartFile file) {
        owner(userId);
        FileAnalysisUpload upload = FileAnalysisUpload.read(file, properties.getMaxFileSize());
        String id = UUID.randomUUID().toString();
        long expires = clock.millis() + Math.max(60, Math.min(604800, properties.getRetentionSeconds())) * 1000L;
        JSONObject result = store(userId, id, upload, expires);
        result.put("fileUrl", modelReference(userId, id).url);
        result.put("fileId", id);
        result.put("urlExpiresAt", Math.min(expires, clock.millis() + Math.max(60, Math.min(3600, properties.getModelUrlTtlSeconds())) * 1000));
        return result;
    }

    /** Resolve only this deployment's exact object URL; never fetch a caller-supplied URL. */
    String idFromUrl(Long userId, String url) {
        owner(userId);
        try {
            URI uri = URI.create(url);
            if (uri.getUserInfo() != null || uri.getFragment() != null || !publicEndpoint.getScheme().equalsIgnoreCase(uri.getScheme())
                    || !publicEndpoint.getRawAuthority().equalsIgnoreCase(uri.getRawAuthority())) throw new IllegalArgumentException();
            java.util.regex.Matcher path = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("/" + bucket + "/" + prefix)
                    + "([1-9][0-9]*)/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})").matcher(uri.getRawPath());
            if (!path.matches()) throw new IllegalArgumentException();
            if (!userId.toString().equals(path.group(1))) throw missing();
            // Expired query signatures are not identity. Authenticated access revalidates the object and signs a fresh URL.
            return path.group(2);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new FileAnalysisException(400, "INVALID_FILE_URL", "请使用当前系统上传接口返回的文件 URL");
        }
    }

    JSONObject store(Long userId, String id, FileAnalysisUpload upload, long expires) {
        long start = System.nanoTime();
        log.info("stage=upload_started storage=minio userId={} fileId={} size={} mimeType={}", userId, id, upload.getSize(), upload.mimeType);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("file-name", Base64.getUrlEncoder().withoutPadding().encodeToString(upload.fileName.getBytes(StandardCharsets.UTF_8)));
        metadata.put("expires-at", Long.toString(expires));
        try (InputStream input = new ByteArrayInputStream(upload.getBytes())) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key(userId, id))
                    .stream(input, upload.getSize(), -1).contentType(upload.mimeType).userMetadata(metadata)
                    .headers(Collections.singletonMap("Cache-Control", "private, no-store")).build());
        } catch (Exception e) {
            log.warn("stage=upload_failed storage=minio userId={} fileId={} exceptionType={}", userId, id, e.getClass().getSimpleName());
            throw unavailable();
        }
        log.info("stage=upload_completed storage=minio userId={} fileId={} durationMs={}", userId, id, (System.nanoTime() - start) / 1000000);
        cleanupExpired(userId);
        JSONObject result = new JSONObject();
        result.put("fileUrl", FileAnalysisStorage.URL_PREFIX + id); result.put("fileName", upload.fileName);
        result.put("mimeType", upload.mimeType); result.put("size", upload.getSize()); result.put("expiresAt", expires);
        return result;
    }

    FileAnalysisReference importLocal(Long userId, String id, FileAnalysisUpload file, long expiresAt) {
        try {
            StatObjectResponse existing = describe(userId, id);
            if (existing.size() != file.getSize() || !name(existing).equals(file.fileName) || expires(existing) != expiresAt) throw unavailable();
        } catch (FileAnalysisException e) {
            if (e.getStatus() != 404) throw e;
            store(userId, id, file, expiresAt);
        }
        return modelReference(userId, id);
    }

    FileAnalysisReference modelReference(Long userId, String id) {
        StatObjectResponse stat = describe(userId, id);
        long remaining = (expires(stat) - clock.millis()) / 1000;
        int ttl = (int) Math.min(remaining, Math.max(60, Math.min(3600, properties.getModelUrlTtlSeconds())));
        if (ttl < 1) throw expired();
        try {
            // SigV4 binds the HTTP method. A GET signature cannot be reused for the HEAD preflight.
            String get = publicClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucket).object(key(userId, id)).method(Method.GET).expiry(ttl).build());
            String head = publicClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucket).object(key(userId, id)).method(Method.HEAD).expiry(ttl).build());
            log.info("stage=file_reference_ready storage=minio userId={} fileId={} ttlSeconds={}", userId, id, ttl);
            return new FileAnalysisReference(name(stat), mime(stat), stat.size(), get, head);
        } catch (Exception e) { throw unavailable(); }
    }

    FileAnalysisUpload load(Long userId, String id) {
        StatObjectResponse stat = describe(userId, id);
        try (InputStream input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key(userId, id))
                .versionId(stat.versionId()).build()); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > properties.getMaxFileSize()) throw unavailable();
                output.write(buffer, 0, count);
            }
            if (output.size() != stat.size()) throw unavailable();
            return new FileAnalysisUpload(name(stat), mime(stat), output.toByteArray());
        } catch (FileAnalysisException e) { throw e; }
        catch (Exception e) { throw unavailable(); }
    }

    void delete(Long userId, String id) {
        try {
            StatObjectResponse file = stat(userId, id);
            if ("true".equals(file.userMetadata().get("record-owned"))) throw new FileAnalysisException(409, "FILE_IN_USE", "文件已关联分析记录，不能作为临时文件删除");
            remove(userId, id, file);
        }
        catch (FileAnalysisException e) { if (e.getStatus() != 404) throw e; }
    }

    void retainForRecord(Long userId, String id) {
        StatObjectResponse file = describe(userId, id);
        if ("true".equals(file.userMetadata().get("record-owned"))) return;
        Map<String, String> metadata = new HashMap<>(file.userMetadata());
        metadata.put("record-owned", "true"); metadata.put("expires-at", Long.toString(FileAnalysisStorage.RECORD_RETENTION));
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", mime(file)); headers.put("Cache-Control", "private, no-store");
        try {
            client.copyObject(CopyObjectArgs.builder().bucket(bucket).object(key(userId, id))
                    .source(CopySource.builder().bucket(bucket).object(key(userId, id)).versionId(file.versionId()).build())
                    .metadataDirective(Directive.REPLACE).headers(headers).userMetadata(metadata).build());
            if (file.versionId() != null) client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key(userId, id)).versionId(file.versionId()).build());
            log.info("stage=file_retained_for_record userId={} fileId={}", userId, id);
        } catch (Exception e) { throw unavailable(); }
    }

    private StatObjectResponse stat(Long userId, String id) {
        try { return client.statObject(StatObjectArgs.builder().bucket(bucket).object(key(userId, id)).build()); }
        catch (ErrorResponseException e) {
            if (Arrays.asList("NoSuchKey", "NoSuchObject").contains(e.errorResponse().code())) throw missing();
            throw unavailable();
        } catch (FileAnalysisException e) { throw e; }
        catch (Exception e) { throw unavailable(); }
    }

    private StatObjectResponse describe(Long userId, String id) {
        StatObjectResponse stat = stat(userId, id);
        if (expires(stat) <= clock.millis()) {
            remove(userId, id, stat);
            throw expired();
        }
        if (stat.size() > Math.max(1, properties.getMaxFileSize())) throw new FileAnalysisException(413, "FILE_TOO_LARGE", "文件超过服务器允许的大小");
        if (stat.size() <= 0) throw missing();
        name(stat); mime(stat);
        return stat;
    }

    private long expires(StatObjectResponse stat) {
        try { return Long.parseLong(stat.userMetadata().get("expires-at")); }
        catch (RuntimeException e) { throw missing(); }
    }

    private String name(StatObjectResponse stat) {
        try {
            String name = new String(Base64.getUrlDecoder().decode(stat.userMetadata().get("file-name")), StandardCharsets.UTF_8);
            if (name.isEmpty() || name.length() > 200 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(c -> c < 32 || c == 127)) throw missing();
            return name;
        } catch (RuntimeException e) { throw missing(); }
    }

    private String mime(StatObjectResponse stat) {
        String type = stat.contentType();
        if (type == null || !type.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) throw missing();
        return type;
    }

    private void remove(Long userId, String id, StatObjectResponse stat) {
        try {
            // Upload IDs are immutable. Purge our exact version instead of leaving a delete marker.
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key(userId, id)).versionId(stat.versionId()).build());
            log.info("stage=file_deleted storage=minio userId={} fileId={}", userId, id);
        } catch (Exception e) { throw unavailable(); }
    }

    private void cleanupExpired(Long userId) {
        long now = clock.millis(), last = lastCleanup.get();
        if (now - last < 60000 || !lastCleanup.compareAndSet(last, now)) return;
        String ownedPrefix = prefix + userId + "/";
        int scanned = 0;
        try {
            for (Result<Item> result : client.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(ownedPrefix).recursive(true).build())) {
                if (++scanned > 1000) break;
                Item item = result.get();
                String id = item.objectName().substring(ownedPrefix.length());
                if (!id.matches("[0-9a-f-]{36}") || item.lastModified().toInstant().toEpochMilli() > now - 60000) continue;
                StatObjectResponse stat = stat(userId, id);
                if (expires(stat) <= now) remove(userId, id, stat);
            }
        } catch (Exception ignored) { /* Best-effort, owner-scoped cleanup must not fail a successful upload. */ }
    }

    private String key(Long userId, String id) {
        owner(userId);
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw missing();
        return prefix + userId + "/" + id;
    }
    private void owner(Long userId) { if (userId == null || userId <= 0) throw new FileAnalysisException(401, "UNAUTHORIZED", "请先登录"); }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String value(String setting, JSONObject file, String key) { return blank(setting) ? file.getString(key) : setting; }
    private static String endpoint(String value) {
        URI uri = URI.create(value.replaceAll("/+$", ""));
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())) throw new IllegalArgumentException();
        return uri.toASCIIString();
    }
    private FileAnalysisException missing() { return new FileAnalysisException(404, "FILE_NOT_FOUND", "文件不存在或不属于当前用户，请重新上传"); }
    private FileAnalysisException expired() { return new FileAnalysisException(410, "FILE_EXPIRED", "上传文件已过期，请重新上传"); }
    private FileAnalysisException unavailable() { return new FileAnalysisException(502, "FILE_STORAGE_UNAVAILABLE", "MinIO 文件存储访问失败，请稍后重试或联系管理员检查配置"); }
}
