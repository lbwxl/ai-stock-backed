package com.ruoyi.webui.service;

import com.ruoyi.webui.domain.AiModel;
import com.ruoyi.webui.domain.vo.AiModelVo;
import java.util.List;

public interface IAiModelService {
    public AiModel selectAiModelById(Long id);

    public List<AiModelVo> selectAiModelList(AiModel aiModel);

    public int insertAiModel(AiModel aiModel);

    public int updateAiModel(AiModel aiModel);

    public int deleteAiModelByIds(Long[] ids);

    public int deleteAiModelById(Long id);

    public List<AiModel> list(Long id);
}
