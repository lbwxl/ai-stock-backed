package com.ruoyi.webui.service.impl;

import com.ruoyi.webui.domain.UserWebuiConfig;
import com.ruoyi.webui.domain.vo.SysLoginVo;
import com.ruoyi.webui.domain.vo.UserWebuiConfigVo;
import com.ruoyi.webui.mapper.UserWebuiConfigMapper;
import com.ruoyi.webui.service.IUserWebuiConfigService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserWebuiConfigServiceImpl implements IUserWebuiConfigService {
    @Autowired
    private UserWebuiConfigMapper userWebuiConfigMapper;

    @Override
    public SysLoginVo selectUserConfigByUserId(Long userId) {
        return this.userWebuiConfigMapper.selectUserConfigByUserId(userId);
    }

    @Override
    public UserWebuiConfig selectUserWebuiConfigById(Long id) {
        return this.userWebuiConfigMapper.selectUserWebuiConfigById(id);
    }

    @Override
    public List<UserWebuiConfigVo> selectUserWebuiConfigList(UserWebuiConfig userWebuiConfig) {
        return this.userWebuiConfigMapper.selectUserWebuiConfigList(userWebuiConfig);
    }

    @Override
    public int insertUserWebuiConfig(UserWebuiConfig userWebuiConfig) {
        return this.userWebuiConfigMapper.insertUserWebuiConfig(userWebuiConfig);
    }

    @Override
    public int updateUserWebuiConfig(UserWebuiConfig userWebuiConfig) {
        return this.userWebuiConfigMapper.updateUserWebuiConfig(userWebuiConfig);
    }

    @Override
    public int deleteUserWebuiConfigByIds(Long[] ids) {
        return this.userWebuiConfigMapper.deleteUserWebuiConfigByIds(ids);
    }

    @Override
    public int deleteUserWebuiConfigById(Long id) {
        return this.userWebuiConfigMapper.deleteUserWebuiConfigById(id);
    }
}
