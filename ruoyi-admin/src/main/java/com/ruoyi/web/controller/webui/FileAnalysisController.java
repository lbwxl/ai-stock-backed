package com.ruoyi.web.controller.webui;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.annotation.Anonymous;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.webui.file.FileAnalysisStorage;
import com.ruoyi.webui.file.FileAnalysisUpload;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import com.ruoyi.webui.file.FileAnalysisException;
import com.ruoyi.webui.file.FileAnalysisService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** File bytes go to the logged-in user's model; only model tool calls invoke an external handler. */
@RestController
@RequestMapping("/webui/file-analysis")
public class FileAnalysisController extends BaseController {
    private final FileAnalysisService service;
    private final FileAnalysisStorage storage;

    public FileAnalysisController(FileAnalysisService service, FileAnalysisStorage storage) {
        this.service = service;
        this.storage = storage;
    }

    @PostMapping(value = "/files", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file) {
        return traced("upload", requestId -> {
            JSONObject result = storage.upload(getUserId(), file);
            result.put("requestId", requestId);
            return ResponseEntity.ok(result);
        });
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<?> download(@PathVariable String id) {
        return traced("download", requestId -> {
            FileAnalysisUpload file = storage.load(getUserId(), FileAnalysisStorage.URL_PREFIX + id);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff").body(file.getBytes());
        });
    }

    @Anonymous
    @GetMapping("/model-files/{userId}/{id}/{expires}/{signature}/{name}")
    public ResponseEntity<byte[]> modelDownload(@PathVariable Long userId, @PathVariable String id,
            @PathVariable long expires, @PathVariable String signature, @PathVariable String name) {
        FileAnalysisUpload file = storage.loadForModel(userId, id, expires, signature);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mimeType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff").body(file.getBytes());
    }

    @DeleteMapping("/files/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return traced("delete", requestId -> {
            storage.delete(getUserId(), FileAnalysisStorage.URL_PREFIX + id);
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> analyze(@RequestBody JSONObject body) {
        return traced("analyze", requestId -> {
        if (body == null || !Arrays.asList("fileUrl", "processingRequirement", "analysisRequirement").containsAll(body.keySet())) {
            throw new FileAnalysisException(400, "INVALID_REQUEST", "仅接受文件 URL 和处理、分析要求");
        }
        for (String field : Arrays.asList("fileUrl", "processingRequirement", "analysisRequirement")) {
            if (!(body.get(field) instanceof String) || body.getString(field).trim().isEmpty()) {
                throw new FileAnalysisException(400, "INVALID_REQUEST", "请先上传文件并填写处理、分析要求");
            }
        }
            Long userId = getUserId();
            return ResponseEntity.ok(service.analyze(userId, requestId, storage.modelReference(userId, body.getString("fileUrl")),
                    body.getString("processingRequirement"), body.getString("analysisRequirement")));
        });
    }

    private ResponseEntity<?> traced(String action, Function<String, ResponseEntity<?>> operation) {
        String requestId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        MDC.put("fileAnalysisRequestId", requestId);
        try {
            logger.info("stage=request_started action={} userId={}", action, getUserId());
            ResponseEntity<?> response = operation.apply(requestId);
            logger.info("stage=request_completed action={} httpStatus={} durationMs={}", action,
                    response.getStatusCodeValue(), (System.nanoTime() - start) / 1000000);
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                    .header("X-Request-Id", requestId).body(response.getBody());
        } catch (Exception e) {
            return failureResponse(e, requestId);
        } finally { MDC.remove("fileAnalysisRequestId"); }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(Exception e) {
        String requestId = UUID.randomUUID().toString();
        return failureResponse(e, requestId);
    }

    private ResponseEntity<?> failureResponse(Exception e, String requestId) {
        if (e instanceof FileAnalysisException) {
            FileAnalysisException failure = (FileAnalysisException) e;
            logger.warn("stage=request_failed requestId={} httpStatus={} code={}", requestId, failure.getStatus(), failure.getCode());
            return error(failure.getStatus(), failure.getCode(), failure.getMessage(), requestId);
        }
        if (e instanceof HttpMediaTypeNotSupportedException) {
            return error(415, "INVALID_REQUEST", "分析接口请使用 JSON 提交已上传的文件 URL", requestId);
        }
        if (e instanceof MaxUploadSizeExceededException) {
            return error(413, "FILE_TOO_LARGE", "文件超过服务器允许的大小", requestId);
        }
        if (e instanceof HttpMessageNotReadableException || e instanceof MissingServletRequestParameterException || e instanceof MissingServletRequestPartException) {
            return error(400, "INVALID_REQUEST", "请求缺少必填字段或格式错误，请先上传文件", requestId);
        }
        // Do not expose uploaded data, provider responses, keys, or adapter exceptions to the browser/log.
        logger.error("stage=request_failed requestId={} exceptionType={}", requestId, e.getClass().getSimpleName());
        return error(500, "ANALYSIS_FAILED", "文件分析失败，请稍后重试。请求编号：" + requestId, requestId);
    }

    private ResponseEntity<Map<String, String>> error(int status, String code, String message, String requestId) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
