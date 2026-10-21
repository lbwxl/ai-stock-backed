package com.ruoyi.webui.service;

import com.ruoyi.webui.domain.AiService;
import java.util.List;

public interface IAiServiceService {
    public AiService selectAiServiceById(Long id);

    public List<AiService> selectAiServiceList(AiService aiService);

    public int insertAiService(AiService aiService);

    public int updateAiService(AiService aiService);

    public int deleteAiServiceByIds(Long[] ids);

    public int deleteAiServiceById(Long id);

    public List<AiService> list();
}
