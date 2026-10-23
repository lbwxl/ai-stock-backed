package com.ruoyi.webui.mapper;

import com.ruoyi.webui.task.FileAnalysisTask;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FileAnalysisTaskMapper {
    List<FileAnalysisTask> selectList(FileAnalysisTask filter);
    FileAnalysisTask selectById(Long id);
    FileAnalysisTask selectByIdForUpdate(Long id);
    int insert(FileAnalysisTask task);
    int maintain(FileAnalysisTask task);
    List<Long> selectPending();
    int claim(@Param("id") Long id, @Param("requestId") String requestId, @Param("workerId") String workerId);
    int finish(FileAnalysisTask task);
    int releaseClaim(@Param("id") Long id, @Param("version") Integer version);
    int recoverInterrupted(@Param("workerId") String workerId);
    int recoverStale();
    int countFileReferences(@Param("userId") Long userId, @Param("fileId") String fileId);
}
