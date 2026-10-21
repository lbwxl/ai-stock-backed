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
import com.ruoyi.webui.domain.AiService;
import com.ruoyi.webui.service.IAiServiceService;
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

@Api(tags = "AI 服务管理")
@RestController
@RequestMapping(value={"/webui/service"})
public class AiServiceController extends BaseController {
    @Autowired
    private IAiServiceService aiServiceService;

    @PreAuthorize(value="@ss.hasPermi('webui:service:list')")
    @ApiOperation(value = "分页查询AI 服务")
    @ApiImplicitParams({
        @ApiImplicitParam(name = "pageNum", value = "页码，从 1 开始", paramType = "query", dataTypeClass = Integer.class, defaultValue = "1"),
        @ApiImplicitParam(name = "pageSize", value = "每页条数", paramType = "query", dataTypeClass = Integer.class, defaultValue = "10")
    })
    @GetMapping(value={"/list"})
    public TableDataInfo list(AiService aiService) {
        this.startPage();
        List<AiService> list = this.aiServiceService.selectAiServiceList(aiService);
        return this.getDataTable(list);
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:export')")
    @Log(title="AI服务", businessType=BusinessType.EXPORT)
    @ApiOperation(value = "导出AI 服务 Excel")
    @PostMapping(value={"/export"})
    public void export(HttpServletResponse response, AiService aiService) {
        List<AiService> list = this.aiServiceService.selectAiServiceList(aiService);
        ExcelUtil<AiService> util = new ExcelUtil<AiService>(AiService.class);
        util.exportExcel(response, list, "AI服务数据");
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:query')")
    @ApiOperation(value = "查询AI 服务详情")
    @GetMapping(value={"/{id}"})
    public AjaxResult getInfo(@PathVariable(value="id") Long id) {
        return this.success(this.aiServiceService.selectAiServiceById(id));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:add')")
    @Log(title="AI服务", businessType=BusinessType.INSERT)
    @ApiOperation(value = "新增AI 服务")
    @PostMapping
    public AjaxResult add(@RequestBody AiService aiService) {
        return this.toAjax(this.aiServiceService.insertAiService(aiService));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:edit')")
    @Log(title="AI服务", businessType=BusinessType.UPDATE)
    @ApiOperation(value = "修改AI 服务")
    @PutMapping
    public AjaxResult edit(@RequestBody AiService aiService) {
        return this.toAjax(this.aiServiceService.updateAiService(aiService));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:remove')")
    @Log(title="AI服务", businessType=BusinessType.DELETE)
    @ApiOperation(value = "删除AI 服务")
    @DeleteMapping(value={"/{ids}"})
    public AjaxResult remove(@PathVariable Long[] ids) {
        return this.toAjax(this.aiServiceService.deleteAiServiceByIds(ids));
    }

    @ApiOperation(value = "查询AI 服务下拉选项")
    @GetMapping(value={"/select/list"})
    public AjaxResult selectList() {
        return this.success(this.aiServiceService.list());
    }
}
