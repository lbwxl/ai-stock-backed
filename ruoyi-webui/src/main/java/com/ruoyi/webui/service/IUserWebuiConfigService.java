package com.ruoyi.webui.service;

import com.ruoyi.webui.domain.UserWebuiConfig;
import com.ruoyi.webui.domain.vo.SysLoginVo;
import com.ruoyi.webui.domain.vo.UserWebuiConfigVo;
import java.util.List;

public interface IUserWebuiConfigService {
    public SysLoginVo selectUserConfigByUserId(Long userId);

    public UserWebuiConfig selectUserWebuiConfigById(Long id);

    public List<UserWebuiConfigVo> selectUserWebuiConfigList(UserWebuiConfig userWebuiConfig);

    public int insertUserWebuiConfig(UserWebuiConfig userWebuiConfig);

    public int updateUserWebuiConfig(UserWebuiConfig userWebuiConfig);

    public int deleteUserWebuiConfigByIds(Long[] ids);

    public int deleteUserWebuiConfigById(Long id);
}
