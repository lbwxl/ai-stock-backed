package com.ruoyi.web.controller.webui;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.webui.file.FileAnalysisException;
import com.ruoyi.webui.file.FileAnalysisUpload;
import com.ruoyi.webui.task.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/webui/file-analysis")
public class FileAnalysisTaskController extends BaseController {
    private final FileAnalysisTaskService service;
    public FileAnalysisTaskController(FileAnalysisTaskService service){this.service=service;}

    @PostMapping("/tasks")
    public AjaxResult create(@RequestBody JSONObject body){return traced(() -> success(service.create(getUserId(),getUsername(),body)));}

    @GetMapping("/tasks")
    public TableDataInfo mine(@RequestParam(required=false) String status,@RequestParam(required=false) String fileName){
        FileAnalysisTask filter=new FileAnalysisTask();filter.setUserId(getUserId());filter.setStatus(status);filter.setFileName(fileName);
        startPage();return getDataTable(service.list(filter));
    }

    @GetMapping("/tasks/{id}")
    public AjaxResult mine(@PathVariable Long id){return success(service.getOwn(id,getUserId()));}

    @PreAuthorize("@ss.hasPermi('webui:fileAnalysis:list')")
    @GetMapping("/records/list")
    public TableDataInfo records(FileAnalysisTask filter){startPage();return getDataTable(service.list(filter));}

    @PreAuthorize("@ss.hasPermi('webui:fileAnalysis:query')")
    @GetMapping("/records/{id}")
    public AjaxResult record(@PathVariable Long id){return success(service.getAdmin(id));}

    @PreAuthorize("@ss.hasPermi('webui:fileAnalysis:query')")
    @GetMapping("/records/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        return traced(() -> {
            FileAnalysisUpload file = service.downloadFile(id, getUsername());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.getSize())
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(file.fileName, StandardCharsets.UTF_8).build().toString())
                    .header("Download-Filename", UriUtils.encodePathSegment(file.fileName, StandardCharsets.UTF_8))
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("X-Request-Id", MDC.get("fileAnalysisRequestId"))
                    .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition, Download-Filename, X-Request-Id")
                    .body(file.getBytes());
        });
    }

    @PreAuthorize("@ss.hasPermi('webui:fileAnalysis:edit')")
    @PutMapping("/records")
    public AjaxResult maintain(@RequestBody JSONObject body){
        return traced(() -> {
            FileAnalysisTask saved=service.maintain(getUsername(),body);
            // Refresh the signed file URL only after the result transaction released its row lock.
            return success(service.getAdmin(saved.getId()));
        });
    }

    private <T> T traced(Supplier<T> work){
        String requestId=UUID.randomUUID().toString();MDC.put("fileAnalysisRequestId",requestId);
        try{return work.get();}finally{MDC.remove("fileAnalysisRequestId");}
    }

    @ExceptionHandler(FileAnalysisException.class)
    public ResponseEntity<?> failure(FileAnalysisException error){
        String requestId=UUID.randomUUID().toString();
        JSONObject body=new JSONObject();body.put("requestId",requestId);body.put("code",error.getCode());body.put("message",error.getMessage());
        logger.warn("stage=task_request_failed requestId={} code={}",requestId,error.getCode());
        return ResponseEntity.status(error.getStatus()).body(body);
    }
}
