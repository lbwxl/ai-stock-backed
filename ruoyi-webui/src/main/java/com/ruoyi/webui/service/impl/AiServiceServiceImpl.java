package com.ruoyi.webui.service.impl;

import com.ruoyi.webui.domain.AiService;
import com.ruoyi.webui.mapper.AiServiceMapper;
import com.ruoyi.webui.service.IAiServiceService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AiServiceServiceImpl implements IAiServiceService {
    @Autowired
    private AiServiceMapper aiServiceMapper;

    @Override
    public AiService selectAiServiceById(Long id) {
        return this.aiServiceMapper.selectAiServiceById(id);
    }

    @Override
    public List<AiService> selectAiServiceList(AiService aiService) {
        return this.aiServiceMapper.selectAiServiceList(aiService);
    }

    @Override
    public int insertAiService(AiService aiService) {
        return this.aiServiceMapper.insertAiService(aiService);
    }

    @Override
    public int updateAiService(AiService aiService) {
        return this.aiServiceMapper.updateAiService(aiService);
    }

    @Override
    public int deleteAiServiceByIds(Long[] ids) {
        return this.aiServiceMapper.deleteAiServiceByIds(ids);
    }

    @Override
    public int deleteAiServiceById(Long id) {
        return this.aiServiceMapper.deleteAiServiceById(id);
    }

    @Override
    public List<AiService> list() {
        return this.aiServiceMapper.selectList();
    }
}
