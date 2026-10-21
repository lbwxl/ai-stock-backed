package com.ruoyi.webui.mapper;

import com.ruoyi.webui.domain.UserWebuiConfig;
import com.ruoyi.webui.domain.vo.SysLoginVo;
import com.ruoyi.webui.domain.vo.UserWebuiConfigVo;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserWebuiConfigMapper {
    public SysLoginVo selectUserConfigByUserId(Long userId);

    public UserWebuiConfig selectUserWebuiConfigById(Long id);

    public List<UserWebuiConfigVo> selectUserWebuiConfigList(UserWebuiConfig userWebuiConfig);

    public int insertUserWebuiConfig(UserWebuiConfig userWebuiConfig);

    public int updateUserWebuiConfig(UserWebuiConfig userWebuiConfig);

    public int deleteUserWebuiConfigById(Long id);

    public int deleteUserWebuiConfigByIds(Long[] ids);

    public List<SysLoginVo> selectList();
}
