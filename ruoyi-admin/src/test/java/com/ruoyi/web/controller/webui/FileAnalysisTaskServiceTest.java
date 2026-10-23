package com.ruoyi.web.controller.webui;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.webui.file.*;
import com.ruoyi.webui.mapper.FileAnalysisTaskMapper;
import com.ruoyi.webui.task.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.*;
import org.springframework.beans.BeanUtils;
import static org.junit.Assert.*;

public class FileAnalysisTaskServiceTest {
    private Memory mapper;
    private FileAnalysisTaskService service;
    private Supplier<FileAnalysisResult> result;
    private AtomicInteger calls;
    private AtomicInteger pins;
    private Long downloadedOwner;
    private String downloadedUrl;
    private static final String FILE="12345678-1234-1234-1234-123456789abc";
    @Before public void setup() {
        mapper=new Memory();calls=new AtomicInteger();pins=new AtomicInteger();
        result=()->new FileAnalysisResult("request","fixture.pdf","自动分析结果");
        FileAnalysisStorage storage=new FileAnalysisStorage(new FileAnalysisProperties()) {
            @Override public String fileId(Long user,String url){return FILE;}
            @Override public FileAnalysisReference retainForRecord(Long user,String url){pins.incrementAndGet();return modelReference(user,url);}
            @Override public FileAnalysisReference modelReference(Long user,String url){return new FileAnalysisReference("fixture.pdf","application/pdf",20,"https://files.example.test/ai-file/file-analysis/"+user+"/"+FILE+"?signed=fixture");}
            @Override public FileAnalysisUpload load(Long user,String url){downloadedOwner=user;downloadedUrl=url;return new FileAnalysisUpload("原文件 测试.pdf","application/pdf",new byte[]{1,2,3});}
        };
        FileAnalysisService analysis=new FileAnalysisService(null,null,new FileAnalysisProperties(),Collections.emptyList()) {
            @Override public String currentModelName(Long user){return "configured-user-model";}
            @Override public FileAnalysisResult analyzeForTask(Long user,String request,FileAnalysisReference file,String processing,String requirement){calls.incrementAndGet();return result.get();}
        };
        service=new FileAnalysisTaskService(mapper,storage,analysis,Runnable::run,"unit-worker");
    }
    private JSONObject creation(){JSONObject body=new JSONObject();body.put("fileUrl","https://files.example.test/"+FILE);body.put("processingRequirement","处理要求");body.put("analysisRequirement","分析要求");return body;}
    private FileAnalysisTask create(){return service.create(11L,"fixture-user",creation());}
    private JSONObject update(FileAnalysisTask task,String text){JSONObject body=new JSONObject();body.put("id",task.getId());body.put("version",task.getVersion());body.put("analysisText",text);return body;}
    @Test public void createPersistsNotStartedBeforeAnyModelCallThenStoresSuccess() {
        FileAnalysisTask task=create();assertEquals("NOT_STARTED",task.getStatus());assertEquals(0,calls.get());assertEquals(1,pins.get());
        service.run(task.getId());FileAnalysisTask completed=service.getOwn(task.getId(),11L);
        assertEquals("SUCCEEDED",completed.getStatus());assertEquals("自动分析结果",completed.getAnalysisText());assertEquals("configured-user-model",completed.getModelName());assertNotNull(completed.getCompletedAt());assertEquals(1,calls.get());
    }
    @Test public void unsupportedModelWaitsForMaintenanceAndIsNotRequeued() {
        result=()->{throw new FileAnalysisException(503,"FILE_MANUAL_REVIEW_REQUIRED","人工维护");};
        FileAnalysisTask task=create();service.run(task.getId());
        FileAnalysisTask waiting=service.getOwn(task.getId(),11L);assertEquals("PROCESSING",waiting.getStatus());assertEquals("MANUAL",waiting.getProcessingMode());assertNull(waiting.getErrorMessage());assertNull(waiting.getCompletedAt());
        service.dispatch();service.run(task.getId());assertEquals(1,calls.get());
        FileAnalysisTask maintained=service.maintain("maintainer",update(task,"人工分析结果"));
        assertEquals("人工分析结果",service.getOwn(task.getId(),11L).getAnalysisText());assertEquals("maintainer",maintained.getUpdateBy());assertEquals("SUCCEEDED",maintained.getStatus());
    }
    @Test public void actualProviderFailureIsRecordedAsFailure() {
        result=()->{throw new FileAnalysisException(502,"MODEL_AUTH_FAILED","模型服务认证失败");};
        FileAnalysisTask task=create();service.run(task.getId());FileAnalysisTask failed=service.getOwn(task.getId(),11L);
        assertEquals("FAILED",failed.getStatus());assertEquals("MODEL_AUTH_FAILED",failed.getErrorCode());assertNotNull(failed.getCompletedAt());
    }
    @Test public void automaticCompletionDoesNotInvalidateAnOpenMaintenanceForm() {
        FileAnalysisTask opened=create();service.run(opened.getId());
        assertTrue(mapper.selectById(opened.getId()).getVersion()>opened.getVersion());
        FileAnalysisTask saved=service.maintain("maintainer",update(opened,"人工核对结果"));
        assertEquals("人工核对结果",saved.getAnalysisText());assertEquals("MANUAL",saved.getProcessingMode());
        try{service.maintain("another-maintainer",update(opened,"覆盖人工结果"));fail();}
        catch(FileAnalysisException e){assertEquals("TASK_VERSION_CONFLICT",e.getCode());}
        assertEquals("人工核对结果",mapper.selectById(opened.getId()).getAnalysisText());
    }
    @Test public void futureAndMalformedVersionsCannotBypassMaintenanceChecks() {
        FileAnalysisTask task=create();
        JSONObject future=update(task,"结果");future.put("version",100);
        try{service.maintain("maintainer",future);fail();}catch(FileAnalysisException e){assertEquals("TASK_VERSION_CONFLICT",e.getCode());}
        for(Number invalid:Arrays.<Number>asList(-1,0.5,2147483648L)) {
            JSONObject body=update(task,"结果");body.put("version",invalid);
            try{service.maintain("maintainer",body);fail();}catch(FileAnalysisException e){assertEquals("INVALID_REQUEST",e.getCode());}
        }
    }
    @Test public void manualSaveWinsOverALaterAutomaticCompletion() {
        FileAnalysisTask task=create();
        result=()->{FileAnalysisTask current=mapper.selectById(task.getId());assertEquals("PROCESSING",current.getStatus());service.maintain("maintainer",update(task,"人工最终结果"));return new FileAnalysisResult("request","fixture.pdf","迟到的自动结果");};
        service.run(task.getId());FileAnalysisTask saved=service.getOwn(task.getId(),11L);
        assertEquals("人工最终结果",saved.getAnalysisText());assertEquals("MANUAL",saved.getProcessingMode());
    }
    @Test public void staleUpdatesEmptySuccessAndOwnershipBypassAreRejected() {
        FileAnalysisTask task=create();
        try{service.getOwn(task.getId(),22L);fail();}catch(FileAnalysisException e){assertEquals("TASK_NOT_FOUND",e.getCode());}
        try{service.maintain("maintainer",update(task," "));fail();}catch(FileAnalysisException e){assertEquals("EMPTY_ANALYSIS",e.getCode());}
        service.maintain("maintainer",update(task,"首次维护结果"));
        try{service.maintain("maintainer",update(task,"old"));fail();}catch(FileAnalysisException e){assertEquals("TASK_VERSION_CONFLICT",e.getCode());}
        JSONObject attack=creation();attack.put("userId",22L);
        try{service.create(11L,"fixture-user",attack);fail();}catch(FileAnalysisException e){assertEquals("INVALID_REQUEST",e.getCode());}
    }
    @Test public void maintenanceOnlyAcceptsResultAndPreservesFileAndRequirements() {
        FileAnalysisTask task=create();
        for(String field:Arrays.asList("status","processingMode","errorMessage","fileUrl","fileId","fileName","userId","processingRequirement","analysisRequirement")) {
            JSONObject attack=update(task,"维护结果");attack.put(field,"不允许修改");
            try{service.maintain("maintainer",attack);fail("Accepted forbidden field: "+field);}catch(FileAnalysisException e){assertEquals("INVALID_REQUEST",e.getCode());}
        }
        FileAnalysisTask saved=service.maintain("maintainer",update(task,"  维护结果  "));
        assertEquals("维护结果",saved.getAnalysisText());assertEquals("SUCCEEDED",saved.getStatus());assertEquals("MANUAL",saved.getProcessingMode());
        assertEquals(task.getFileId(),saved.getFileId());assertEquals(task.getFileName(),saved.getFileName());assertEquals(task.getUserId(),saved.getUserId());
        assertEquals(task.getProcessingRequirement(),saved.getProcessingRequirement());assertEquals(task.getAnalysisRequirement(),saved.getAnalysisRequirement());
        assertEquals(task.getStartedAt(),saved.getStartedAt());assertNotNull(saved.getCompletedAt());assertEquals(1,pins.get());
    }
    @Test public void savingResultOnFailedRecordClearsFailureWithoutChangingInput() {
        result=()->{throw new FileAnalysisException(502,"MODEL_AUTH_FAILED","认证失败");};
        FileAnalysisTask task=create();service.run(task.getId());FileAnalysisTask failed=service.getOwn(task.getId(),11L);
        FileAnalysisTask saved=service.maintain("maintainer",update(failed,"人工补充结果"));
        assertEquals("SUCCEEDED",saved.getStatus());assertNull(saved.getErrorCode());assertNull(saved.getErrorMessage());
        assertEquals(failed.getProcessingRequirement(),saved.getProcessingRequirement());assertEquals(failed.getAnalysisRequirement(),saved.getAnalysisRequirement());
        assertEquals(failed.getStartedAt(),saved.getStartedAt());
    }
    @Test public void submittedFileIsNotDuplicated() {
        create();try{create();fail();}catch(FileAnalysisException e){assertEquals("FILE_ALREADY_SUBMITTED",e.getCode());}assertEquals(1,pins.get());
    }
    @Test public void adminDownloadReadsTheRecordOwnersOriginalFileAndPreservesTheRecord() {
        FileAnalysisTask task=create();
        FileAnalysisUpload file=service.downloadFile(task.getId(),"admin");
        assertEquals(Long.valueOf(11),downloadedOwner);
        assertEquals(FileAnalysisStorage.URL_PREFIX+FILE,downloadedUrl);
        assertEquals("原文件 测试.pdf",file.fileName);
        assertArrayEquals(new byte[]{1,2,3},file.getBytes());
        assertEquals("NOT_STARTED",mapper.selectById(task.getId()).getStatus());
        assertEquals(0,calls.get());
        try{service.downloadFile(999L,"admin");fail();}catch(FileAnalysisException e){assertEquals("TASK_NOT_FOUND",e.getCode());}
    }
    @Test public void dispatchAndRecoveryPreserveManualWorkAndDoNotQueueTheSamePendingIdTwice() {
        FileAnalysisTask task=create();List<Runnable> queue=new ArrayList<>();
        FileAnalysisStorage storage=new FileAnalysisStorage(new FileAnalysisProperties());
        FileAnalysisTaskService queuedService=new FileAnalysisTaskService(mapper,storage,null,queue::add,"unit-worker");
        queuedService.dispatch();queuedService.dispatch();assertEquals(1,queue.size());
        mapper.claim(task.getId(),"recover-request","unit-worker");queuedService.recover();assertEquals("NOT_STARTED",mapper.selectById(task.getId()).getStatus());
        result=()->{throw new FileAnalysisException(503,"FILE_MANUAL_REVIEW_REQUIRED","人工维护");};
        service.run(task.getId());queuedService.recover();assertEquals("PROCESSING",mapper.selectById(task.getId()).getStatus());assertEquals("MANUAL",mapper.selectById(task.getId()).getProcessingMode());
    }

    static class Memory implements FileAnalysisTaskMapper {
        final Map<Long,FileAnalysisTask> rows=new LinkedHashMap<>();final Map<Long,String> workers=new HashMap<>();long sequence;
        FileAnalysisTask copy(FileAnalysisTask task){if(task==null)return null;FileAnalysisTask out=new FileAnalysisTask();BeanUtils.copyProperties(task,out);return out;}
        public synchronized List<FileAnalysisTask> selectList(FileAnalysisTask filter){List<FileAnalysisTask> out=new ArrayList<>();for(FileAnalysisTask task:rows.values())if(filter.getUserId()==null||filter.getUserId().equals(task.getUserId()))out.add(copy(task));return out;}
        public synchronized FileAnalysisTask selectById(Long id){return copy(rows.get(id));}
        public synchronized FileAnalysisTask selectByIdForUpdate(Long id){return copy(rows.get(id));}
        public synchronized int insert(FileAnalysisTask task){task.setId(++sequence);task.setCreateTime(new Date());task.setUpdateTime(new Date());rows.put(task.getId(),copy(task));return 1;}
        public synchronized int maintain(FileAnalysisTask task){FileAnalysisTask old=rows.get(task.getId());if(old==null||!old.getVersion().equals(task.getVersion()))return 0;FileAnalysisTask saved=copy(task);saved.setVersion(old.getVersion()+1);rows.put(saved.getId(),saved);return 1;}
        public synchronized List<Long> selectPending(){List<Long> ids=new ArrayList<>();for(FileAnalysisTask task:rows.values())if("NOT_STARTED".equals(task.getStatus()))ids.add(task.getId());return ids;}
        public synchronized int claim(Long id,String request,String worker){FileAnalysisTask task=rows.get(id);if(task==null||!"NOT_STARTED".equals(task.getStatus()))return 0;task.setStatus("PROCESSING");task.setProcessingMode("AUTO");task.setRequestId(request);task.setVersion(task.getVersion()+1);task.setStartedAt(new Date());workers.put(id,worker);return 1;}
        public synchronized int finish(FileAnalysisTask task){FileAnalysisTask old=rows.get(task.getId());if(old==null||!"PROCESSING".equals(old.getStatus())||!"AUTO".equals(old.getProcessingMode())||!old.getVersion().equals(task.getVersion()))return 0;FileAnalysisTask saved=copy(task);saved.setVersion(task.getVersion()+1);rows.put(task.getId(),saved);return 1;}
        public synchronized int releaseClaim(Long id,Integer version){FileAnalysisTask task=rows.get(id);if(task==null||!task.getVersion().equals(version))return 0;task.setStatus("NOT_STARTED");task.setVersion(version+1);return 1;}
        public synchronized int recoverInterrupted(String worker){int count=0;for(FileAnalysisTask task:rows.values())if("PROCESSING".equals(task.getStatus())&&"AUTO".equals(task.getProcessingMode())&&worker.equals(workers.get(task.getId()))){task.setStatus("NOT_STARTED");task.setVersion(task.getVersion()+1);count++;}return count;}
        public int recoverStale(){return 0;}
        public synchronized int countFileReferences(Long user,String file){return (int)rows.values().stream().filter(t->user.equals(t.getUserId())&&file.equals(t.getFileId())).count();}
    }
}
