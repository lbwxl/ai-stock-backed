package com.ruoyi.web.controller.webui;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.webui.file.*;
import com.ruoyi.webui.task.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real multipart storage and HTTP download; no model or remote storage calls. */
public class FileAnalysisResultFilesTest {
    @Rule public TemporaryFolder root=new TemporaryFolder();
    private MockMvc mvc;
    private FileAnalysisTaskService service;
    private FileAnalysisStorage storage;
    private final byte[] document=new byte[]{80,75,3,4,0,(byte)255};
    @Before public void setup() {
        FileAnalysisProperties properties=new FileAnalysisProperties();properties.setStoragePath(root.getRoot().getAbsolutePath());
        properties.setPublicBaseUrlFile(null);properties.setPublicBaseUrl("");properties.setRetentionSeconds(60);
        storage=new FileAnalysisStorage(properties,Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneOffset.UTC));
        FileAnalysisTaskServiceTest.Memory tasks=new FileAnalysisTaskServiceTest.Memory();
        FileAnalysisTask task=new FileAnalysisTask();task.setId(1L);task.setUserId(11L);task.setFileId(UUID.randomUUID().toString());task.setVersion(0);task.setStatus("PROCESSING");task.setProcessingMode("MANUAL");tasks.rows.put(1L,task);
        service=new FileAnalysisTaskService(tasks,new FileAnalysisTaskServiceTest.MemoryResults(),storage,null,Runnable::run,"unit");
        mvc=MockMvcBuilders.standaloneSetup(new FileAnalysisTaskController(service)).build();login(1L,"admin");
    }
    private void login(Long id,String name) {LoginUser user=new LoginUser();user.setUserId(id);SysUser principal=new SysUser();principal.setUserId(id);principal.setUserName(name);user.setUser(principal);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"fixture"));}
    @After public void cleanup(){SecurityContextHolder.clearContext();}
    @Test public void multipartFilesPublishAndDownloadWithChineseNamesEvenWhenOriginalFileIsUnavailable() throws Exception {
        MvcResult uploaded=mvc.perform(multipart("/webui/file-analysis/records/1/result-files")
            .file(new MockMultipartFile("files","自然资源巡查报告.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",document))
            .file(new MockMultipartFile("files","问题标注图.png","image/png",new byte[]{(byte)137,80,78,71}))).andReturn();
        assertEquals(200,uploaded.getResponse().getStatus());JSONObject body=JSONObject.parseObject(uploaded.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String id=body.getJSONArray("data").getJSONObject(0).getString("id");assertEquals(2,body.getJSONArray("data").size());
        assertFalse(body.toJSONString().contains("storageUserId"));assertFalse(body.toJSONString().contains("taskId"));
        login(11L,"owner");assertEquals(404,mvc.perform(get("/webui/file-analysis/tasks/1/result-files/"+id+"/download")).andReturn().getResponse().getStatus());
        login(1L,"admin");JSONObject update=new JSONObject();update.put("id",1);update.put("version",0);update.put("resultFileIds",Arrays.asList(id,body.getJSONArray("data").getJSONObject(1).getString("id")));
        assertEquals(200,mvc.perform(put("/webui/file-analysis/records").contentType("application/json").content(update.toJSONString())).andReturn().getResponse().getStatus());
        FileAnalysisTask detail=service.getOwn(1L,11L);assertEquals(Boolean.FALSE,detail.getFileAvailable());assertEquals(2,detail.getResultFiles().size());
        login(11L,"owner");MvcResult download=mvc.perform(get("/webui/file-analysis/tasks/1/result-files/"+id+"/download")).andReturn();
        assertEquals(200,download.getResponse().getStatus());assertArrayEquals(document,download.getResponse().getContentAsByteArray());
        assertEquals("application/octet-stream",download.getResponse().getContentType());assertEquals("nosniff",download.getResponse().getHeader("X-Content-Type-Options"));assertTrue(download.getResponse().getHeader("Content-Disposition").startsWith("attachment;"));
        assertEquals("自然资源巡查报告.docx",java.net.URLDecoder.decode(download.getResponse().getHeader("Download-Filename"),"UTF-8"));
        login(22L,"other");assertEquals(404,mvc.perform(get("/webui/file-analysis/tasks/1/result-files/"+id+"/download")).andReturn().getResponse().getStatus());
        login(11L,"owner");assertEquals(404,mvc.perform(get("/webui/file-analysis/tasks/999/result-files/"+id+"/download")).andReturn().getResponse().getStatus());
        // The file is retained beyond the temporary-upload TTL without needing a public model URL.
        java.nio.file.Path metadata=root.getRoot().toPath().resolve("1").resolve(id).resolve("metadata.json");
        JSONObject meta=JSONObject.parseObject(new String(java.nio.file.Files.readAllBytes(metadata),StandardCharsets.UTF_8));assertTrue(meta.getBooleanValue("recordOwned"));assertEquals(FileAnalysisStorage.RECORD_RETENTION,meta.getLongValue("expiresAt"));
    }
    @Test public void uploadAndAdminDownloadRequireTheirExistingPermissions() throws Exception {
        Class<FileAnalysisTaskController> controller=FileAnalysisTaskController.class;
        assertEquals("@ss.hasPermi('webui:fileAnalysis:edit')",controller.getMethod("uploadResults",Long.class,org.springframework.web.multipart.MultipartFile[].class).getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
        assertEquals("@ss.hasPermi('webui:fileAnalysis:query')",controller.getMethod("downloadAdminResult",Long.class,String.class).getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
    }
}
