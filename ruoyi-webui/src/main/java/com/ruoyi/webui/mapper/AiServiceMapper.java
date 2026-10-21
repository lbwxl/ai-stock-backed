package com.ruoyi.webui.mapper;

import com.ruoyi.webui.domain.AiService;
import java.util.List;

public interface AiServiceMapper {
    public AiService selectAiServiceById(Long id);

    public List<AiService> selectAiServiceList(AiService aiService);

    public int insertAiService(AiService aiService);

    public int updateAiService(AiService aiService);

    public int deleteAiServiceById(Long id);

    public int deleteAiServiceByIds(Long[] ids);

    public List<AiService> selectList();
}
