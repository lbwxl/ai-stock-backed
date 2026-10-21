package com.ruoyi.web.controller.webui;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.bean.BeanUtils;
import com.ruoyi.framework.security.context.AuthenticationContextHolder;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.web.controller.domain.AuthDto;
import com.ruoyi.webui.domain.vo.SysLoginVo;
import com.ruoyi.webui.mapper.UserWebuiConfigMapper;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/webui/auth"})
public class WebUIController {
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private UserWebuiConfigMapper userWebuiConfigMapper;
    @Resource
    private AuthenticationManager authenticationManager;

    // 已停用二开登录接口，保留旧实现供参考。统一使用 POST /login 获取 JWT，
    // 再携带 Bearer token 调用 GET /webui/config/current 获取当前用户配置。
    // @ApiOperation(value = "校验账号并获取 AI 与搜索配置")
    // @PostMapping(value={"/in"})
    public AjaxResult login(@RequestBody AuthDto dto) {
        String username = dto.getUsername();
        String password = dto.getPassword();
        if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password)) {
            return AjaxResult.error(400, "用户名或密码不能为空");
        }
        if (password.length() < 5 || password.length() > 20) {
            return AjaxResult.error(400, "密码长度必须在5-20之间");
        }
        if (username.length() < 2 || username.length() > 20) {
            return AjaxResult.error(400, "用户名长度必须在2-20之间");
        }
        SysUser sysUser = this.sysUserMapper.selectUserByUserName(username);
        if (sysUser == null) {
            return AjaxResult.error(400, "用户名不存在");
        }
        try {
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(username, password);
            AuthenticationContextHolder.setContext(authenticationToken);
            this.authenticationManager.authenticate(authenticationToken);
        }
        catch (Exception e) {
            return AjaxResult.error(400, "用户名或密码错误");
        }
        finally {
            AuthenticationContextHolder.clearContext();
        }
        if (sysUser.getStatus().equals("1")) {
            return AjaxResult.error(400, "用户已被禁用");
        }
        List<SysLoginVo> vos = this.userWebuiConfigMapper.selectList();
        SysLoginVo result = new SysLoginVo();
        for (SysLoginVo vo : vos) {
            if (!vo.getUserId().equals(sysUser.getUserId())) continue;
            BeanUtils.copyProperties(vo, result);
            break;
        }
        return AjaxResult.success("登录成功", result);
    }
}
