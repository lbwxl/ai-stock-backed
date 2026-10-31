package com.ruoyi.webui.mapper;

import com.ruoyi.webui.task.FileAnalysisResultFile;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FileAnalysisResultFileMapper {
    int insert(FileAnalysisResultFile file);
    FileAnalysisResultFile selectById(String id);
    List<FileAnalysisResultFile> selectPublished(Long taskId);
    int unpublish(Long taskId);
    int publish(@Param("taskId") Long taskId, @Param("ids") List<String> ids);
}
