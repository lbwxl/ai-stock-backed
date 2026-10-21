package com.ruoyi.webui.mapper;

import com.ruoyi.webui.domain.AiModel;
import com.ruoyi.webui.domain.vo.AiModelVo;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AiModelMapper {
    public AiModel selectAiModelById(Long id);

    public List<AiModelVo> selectAiModelList(AiModel aiModel);

    public int insertAiModel(AiModel aiModel);

    public int updateAiModel(AiModel aiModel);

    public int deleteAiModelById(Long id);

    public int deleteAiModelByIds(Long[] ids);

    public List<AiModel> selectList(@Param("aiServiceId") Long aiServiceId);
}
