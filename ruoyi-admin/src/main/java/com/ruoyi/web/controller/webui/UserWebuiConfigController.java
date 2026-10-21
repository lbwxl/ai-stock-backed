package com.ruoyi.web.controller.webui;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.webui.domain.UserWebuiConfig;
import com.ruoyi.webui.domain.vo.SysLoginVo;
import com.ruoyi.webui.domain.vo.UserWebuiConfigVo;
import com.ruoyi.webui.service.IUserWebuiConfigService;
import java.util.List;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "用户 AI 配置管理")
@RestController
@RequestMapping(value={"/webui/config"})
public class UserWebuiConfigController extends BaseController {
    @Autowired
    private IUserWebuiConfigService userWebuiConfigService;

    @ApiOperation(value = "查询当前登录用户的 AI 与搜索配置", notes = "需要 Bearer JWT，只根据登录身份查询，不接受用户 ID。不要求配置管理权限；未配置时返回当前用户身份及空的服务配置。")
    @GetMapping("/current")
    public AjaxResult current() {
        SysLoginVo config = this.userWebuiConfigService.selectUserConfigByUserId(getUserId());
        if (config == null) {
            config = new SysLoginVo();
        }
        config.setUserId(getUserId());
        config.setUsername(getUsername());
        return this.success(config);
    }

    @PreAuthorize(value="@ss.hasPermi('webui:config:list')")
    @ApiOperation(value = "分页查询用户配置")
    @ApiImplicitParams({
        @ApiImplicitParam(name = "pageNum", value = "页码，从 1 开始", paramType = "query", dataTypeClass = Integer.class, defaultValue = "1"),
        @ApiImplicitParam(name = "pageSize", value = "每页条数", paramType = "query", dataTypeClass = Integer.class, defaultValue = "10")
    })
    @GetMapping(value={"/list"})
    public TableDataInfo list(UserWebuiConfig userWebuiConfig) {
        this.startPage();
        List<UserWebuiConfigVo> list = this.userWebuiConfigService.selectUserWebuiConfigList(userWebuiConfig);
        return this.getDataTable(list);
    }

    @PreAuthorize(value="@ss.hasPermi('webui:config:export')")
    @Log(title="用户配置", businessType=BusinessType.EXPORT)
    @ApiOperation(value = "导出用户配置 Excel")
    @PostMapping(value={"/export"})
    public void export(HttpServletResponse response, UserWebuiConfig userWebuiConfig) {
        List<UserWebuiConfigVo> list = this.userWebuiConfigService.selectUserWebuiConfigList(userWebuiConfig);
        ExcelUtil<UserWebuiConfigVo> util = new ExcelUtil<UserWebuiConfigVo>(UserWebuiConfigVo.class);
        util.exportExcel(response, list, "用户配置数据");
    }

    @PreAuthorize(value="@ss.hasPermi('webui:config:query')")
    @ApiOperation(value = "查询用户配置详情")
    @GetMapping(value={"/{id}"})
    public AjaxResult getInfo(@PathVariable(value="id") Long id) {
        return this.success(this.userWebuiConfigService.selectUserWebuiConfigById(id));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:config:add')")
    @Log(title="用户配置", businessType=BusinessType.INSERT)
    @ApiOperation(value = "新增用户配置")
    @PostMapping
    public AjaxResult add(@RequestBody UserWebuiConfig userWebuiConfig) {
        return this.toAjax(this.userWebuiConfigService.insertUserWebuiConfig(userWebuiConfig));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:config:edit')")
    @Log(title="用户配置", businessType=BusinessType.UPDATE)
    @ApiOperation(value = "修改用户配置")
    @PutMapping
    public AjaxResult edit(@RequestBody UserWebuiConfig userWebuiConfig) {
        return this.toAjax(this.userWebuiConfigService.updateUserWebuiConfig(userWebuiConfig));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:config:remove')")
    @Log(title="用户配置", businessType=BusinessType.DELETE)
    @ApiOperation(value = "删除用户配置")
    @DeleteMapping(value={"/{ids}"})
    public AjaxResult remove(@PathVariable Long[] ids) {
        return this.toAjax(this.userWebuiConfigService.deleteUserWebuiConfigByIds(ids));
    }
}
