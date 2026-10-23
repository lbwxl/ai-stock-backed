package com.ruoyi.webui.task;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.webui.file.*;
import com.ruoyi.webui.mapper.FileAnalysisTaskMapper;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileAnalysisTaskService {
    public static final String NOT_STARTED="NOT_STARTED", PROCESSING="PROCESSING", SUCCEEDED="SUCCEEDED", FAILED="FAILED";
    private static final Set<String> STATUSES = new HashSet<>(Arrays.asList(NOT_STARTED,PROCESSING,SUCCEEDED,FAILED));
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileAnalysisTaskService.class);
    private final FileAnalysisTaskMapper mapper;
    private final FileAnalysisStorage storage;
    private final FileAnalysisService analysis;
    private final Executor executor;
    private final String workerId;
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    public FileAnalysisTaskService(FileAnalysisTaskMapper mapper, FileAnalysisStorage storage, FileAnalysisService analysis,
            @Qualifier("fileAnalysisTaskExecutor") Executor executor,
            @Value("${webui.file-analysis.worker-id:${HOSTNAME:local}-${server.port:8080}}") String workerId) {
        this.mapper=mapper; this.storage=storage; this.analysis=analysis; this.executor=executor; this.workerId=workerId;
    }

    @Transactional
    public FileAnalysisTask create(Long userId, String actor, JSONObject body) {
        validateBody(body, new HashSet<>(Arrays.asList("fileUrl","processingRequirement","analysisRequirement")));
        String processing = text(body,"processingRequirement",10000,true);
        String requirement = text(body,"analysisRequirement",10000,true);
        String url = text(body,"fileUrl",8192,true);
        String fileId = storage.fileId(userId,url);
        if (mapper.countFileReferences(userId,fileId)>0) throw fail(409,"FILE_ALREADY_SUBMITTED","该文件已经创建分析任务，请查看已有记录");
        FileAnalysisReference file = storage.retainForRecord(userId,url);
        FileAnalysisTask task = new FileAnalysisTask();
        task.setUserId(userId); task.setCreateBy(actor); task.setFileId(fileId); applyFile(task,file);
        task.setProcessingRequirement(processing); task.setAnalysisRequirement(requirement);
        task.setStatus(NOT_STARTED); task.setProcessingMode("AUTO"); task.setVersion(0); task.setRequestId(UUID.randomUUID().toString());
        mapper.insert(task);
        log.info("stage=task_created taskId={} userId={} status={}",task.getId(),userId,NOT_STARTED);
        return withFileUrl(mapper.selectById(task.getId()));
    }

    public List<FileAnalysisTask> list(FileAnalysisTask filter) {
        if (filter.getStatus()!=null && !filter.getStatus().isEmpty() && !STATUSES.contains(filter.getStatus())) throw fail(400,"INVALID_STATUS","不支持的分析状态");
        return mapper.selectList(filter);
    }

    public FileAnalysisTask getOwn(Long id, Long userId) {
        FileAnalysisTask task = required(id);
        if (!userId.equals(task.getUserId())) throw missing();
        return withFileUrl(task);
    }

    public FileAnalysisTask getAdmin(Long id) { return withFileUrl(required(id)); }

    /** The controller checks admin query permission; storage reads the record owner's file. */
    public FileAnalysisUpload downloadFile(Long id, String actor) {
        FileAnalysisTask task = required(id);
        FileAnalysisUpload file = storage.load(task.getUserId(), FileAnalysisStorage.URL_PREFIX + task.getFileId());
        log.info("stage=task_file_downloaded taskId={} userId={} actor={} size={}",
                id, task.getUserId(), actor, file.getSize());
        return file;
    }

    @Transactional
    public FileAnalysisTask maintain(String actor, JSONObject body) {
        validateBody(body,new HashSet<>(Arrays.asList("id","version","analysisText")));
        if (!(body.get("id") instanceof Number) || !(body.get("version") instanceof Number)) throw fail(400,"INVALID_REQUEST","缺少记录编号或版本号");
        Number version=(Number)body.get("version");
        long suppliedVersion=version.longValue();
        if (suppliedVersion<0 || suppliedVersion>Integer.MAX_VALUE || version.doubleValue()!=suppliedVersion) {
            throw fail(400,"INVALID_REQUEST","版本号格式不正确");
        }
        // Read the current committed row under a lock. Automatic transitions may have happened
        // while the editor was open; only a newer human edit must block this result-only save.
        FileAnalysisTask task=mapper.selectByIdForUpdate(body.getLong("id"));
        if (task==null) throw missing();
        boolean humanMaintained=(task.getUpdateBy()!=null && !task.getUpdateBy().trim().isEmpty())
                || ("MANUAL".equals(task.getProcessingMode()) && task.getAnalysisText()!=null && !task.getAnalysisText().trim().isEmpty());
        if (suppliedVersion!=task.getVersion() && (suppliedVersion>task.getVersion() || humanMaintained)) throw conflict();
        String result=text(body,"analysisText",1024*1024,false);
        if (result.isEmpty()) throw fail(400,"EMPTY_ANALYSIS","请填写分析结果");
        task.setAnalysisText(result);
        task.setStatus(SUCCEEDED); task.setProcessingMode("MANUAL");
        task.setErrorCode(null); task.setErrorMessage(null); task.setCompletedAt(new Date());
        task.setUpdateBy(actor);
        if (mapper.maintain(task)!=1) throw conflict();
        log.info("stage=task_maintained taskId={} status={} mode={}",task.getId(),SUCCEEDED,task.getProcessingMode());
        return required(task.getId());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int count=mapper.recoverInterrupted(workerId);
        if(count>0)log.info("stage=task_recovered count={}",count);
    }

    @Scheduled(fixedDelayString="${webui.file-analysis.task-poll-interval:3000}",initialDelay=3000)
    public void dispatch() {
        mapper.recoverStale();
        for(Long id:mapper.selectPending()) {
            if(!queued.add(id)) continue;
            try { executor.execute(() -> { try { run(id); } finally { queued.remove(id); } }); }
            catch(RejectedExecutionException e) { queued.remove(id); break; }
        }
    }

    public void run(Long id) {
        String requestId=UUID.randomUUID().toString();
        if(mapper.claim(id,requestId,workerId)!=1)return;
        FileAnalysisTask task=mapper.selectById(id);
        if(task==null||!requestId.equals(task.getRequestId())||!PROCESSING.equals(task.getStatus())||!"AUTO".equals(task.getProcessingMode()))return;
        MDC.put("fileAnalysisRequestId",requestId);
        try {
            log.info("stage=task_started taskId={} userId={}",id,task.getUserId());
            task.setModelName(analysis.currentModelName(task.getUserId()));
            FileAnalysisResult result=analysis.analyzeForTask(task.getUserId(),requestId,
                    storage.modelReference(task.getUserId(),FileAnalysisStorage.URL_PREFIX+task.getFileId()),
                    task.getProcessingRequirement(),task.getAnalysisRequirement());
            task.setAnalysisText(result.analysisText); task.setStatus(SUCCEEDED); task.setCompletedAt(new Date());
            task.setErrorCode(null); task.setErrorMessage(null);
        } catch(FileAnalysisException e) {
            if(Arrays.asList("FILE_MANUAL_REVIEW_REQUIRED","MODEL_CANNOT_READ_FILE","FILE_TOOL_NOT_CONFIGURED").contains(e.getCode())) {
                task.setStatus(PROCESSING); task.setProcessingMode("MANUAL"); task.setErrorCode(null); task.setErrorMessage(null);
                log.info("stage=task_waiting_manual taskId={}",id);
            } else {
                task.setStatus(FAILED); task.setErrorCode(e.getCode()); task.setErrorMessage(e.getMessage()); task.setCompletedAt(new Date());
            }
        } catch(Exception e) {
            task.setStatus(FAILED); task.setErrorCode("ANALYSIS_FAILED"); task.setErrorMessage("分析处理失败，请联系后台维护分析结果");task.setCompletedAt(new Date());
            log.warn("stage=task_exception taskId={} exceptionType={}",id,e.getClass().getSimpleName());
        } finally {
            try {
                int updated=mapper.finish(task);
                log.info("stage=task_finished taskId={} status={} mode={} persisted={}",id,task.getStatus(),task.getProcessingMode(),updated==1);
            } finally { MDC.remove("fileAnalysisRequestId"); }
        }
    }

    private FileAnalysisTask required(Long id) { FileAnalysisTask task=mapper.selectById(id); if(task==null)throw missing(); return task; }
    private FileAnalysisTask withFileUrl(FileAnalysisTask task) {
        try { task.setFileUrl(storage.modelReference(task.getUserId(),FileAnalysisStorage.URL_PREFIX+task.getFileId()).url);task.setFileAvailable(true); }
        catch(FileAnalysisException e) { task.setFileUrl(null);task.setFileAvailable(false);task.setFileErrorMessage(e.getMessage()); }
        return task;
    }
    private void applyFile(FileAnalysisTask task, FileAnalysisReference file) {
        task.setFileName(file.fileName);task.setMimeType(file.mimeType);task.setFileSize(file.size);
        URI uri=URI.create(file.url);task.setFileUrl(uri.getScheme()+"://"+uri.getRawAuthority()+uri.getRawPath());
    }
    private void validateBody(JSONObject body,Set<String> allowed) { if(body==null||!allowed.containsAll(body.keySet()))throw fail(400,"INVALID_REQUEST","请求参数不正确"); }
    private String text(JSONObject body,String field,int max,boolean required) {
        Object value=body.get(field);
        if(value==null&&!required)return "";
        if(!(value instanceof String))throw fail(400,"INVALID_REQUEST","字段格式不正确："+field);
        String text=((String)value).trim();
        if((required&&text.isEmpty())||text.length()>max)throw fail(400,"INVALID_REQUEST","字段为空或长度超限："+field);
        return text;
    }
    private FileAnalysisException missing(){return fail(404,"TASK_NOT_FOUND","分析记录不存在或不属于当前用户");}
    private FileAnalysisException conflict(){return fail(409,"TASK_VERSION_CONFLICT","分析结果已被其他维护操作更新，请重新打开记录核对后再保存");}
    private FileAnalysisException fail(int status,String code,String message){return new FileAnalysisException(status,code,message);}
}
