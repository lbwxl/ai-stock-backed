package com.ruoyi.webui.service.impl;

import com.ruoyi.webui.domain.AiModel;
import com.ruoyi.webui.domain.vo.AiModelVo;
import com.ruoyi.webui.mapper.AiModelMapper;
import com.ruoyi.webui.service.IAiModelService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AiModelServiceImpl implements IAiModelService {
    @Autowired
    private AiModelMapper aiModelMapper;

    @Override
    public AiModel selectAiModelById(Long id) {
        return this.aiModelMapper.selectAiModelById(id);
    }

    @Override
    public List<AiModelVo> selectAiModelList(AiModel aiModel) {
        return this.aiModelMapper.selectAiModelList(aiModel);
    }

    @Override
    public int insertAiModel(AiModel aiModel) {
        return this.aiModelMapper.insertAiModel(aiModel);
    }

    @Override
    public int updateAiModel(AiModel aiModel) {
        return this.aiModelMapper.updateAiModel(aiModel);
    }

    @Override
    public int deleteAiModelByIds(Long[] ids) {
        return this.aiModelMapper.deleteAiModelByIds(ids);
    }

    @Override
    public int deleteAiModelById(Long id) {
        return this.aiModelMapper.deleteAiModelById(id);
    }

    @Override
    public List<AiModel> list(Long id) {
        return this.aiModelMapper.selectList(id);
    }
}
