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
import com.ruoyi.webui.domain.AiModel;
import com.ruoyi.webui.domain.vo.AiModelVo;
import com.ruoyi.webui.service.IAiModelService;
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

@Api(tags = "AI 模型管理")
@RestController
@RequestMapping(value={"/webui/model"})
public class AiModelController extends BaseController {
    @Autowired
    private IAiModelService aiModelService;

    @PreAuthorize(value="@ss.hasPermi('webui:model:list')")
    @ApiOperation(value = "分页查询AI 模型")
    @ApiImplicitParams({
        @ApiImplicitParam(name = "pageNum", value = "页码，从 1 开始", paramType = "query", dataTypeClass = Integer.class, defaultValue = "1"),
        @ApiImplicitParam(name = "pageSize", value = "每页条数", paramType = "query", dataTypeClass = Integer.class, defaultValue = "10")
    })
    @GetMapping(value={"/list"})
    public TableDataInfo list(AiModel aiModel) {
        this.startPage();
        List<AiModelVo> list = this.aiModelService.selectAiModelList(aiModel);
        return this.getDataTable(list);
    }

    @PreAuthorize(value="@ss.hasPermi('webui:model:export')")
    @Log(title="AI模型", businessType=BusinessType.EXPORT)
    @ApiOperation(value = "导出AI 模型 Excel")
    @PostMapping(value={"/export"})
    public void export(HttpServletResponse response, AiModel aiModel) {
        List<AiModelVo> list = this.aiModelService.selectAiModelList(aiModel);
        ExcelUtil<AiModelVo> util = new ExcelUtil<AiModelVo>(AiModelVo.class);
        util.exportExcel(response, list, "AI模型数据");
    }

    @PreAuthorize(value="@ss.hasPermi('webui:model:query')")
    @ApiOperation(value = "查询AI 模型详情")
    @GetMapping(value={"/{id}"})
    public AjaxResult getInfo(@PathVariable(value="id") Long id) {
        return this.success(this.aiModelService.selectAiModelById(id));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:model:add')")
    @Log(title="AI模型", businessType=BusinessType.INSERT)
    @ApiOperation(value = "新增AI 模型")
    @PostMapping
    public AjaxResult add(@RequestBody AiModel aiModel) {
        return this.toAjax(this.aiModelService.insertAiModel(aiModel));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:model:edit')")
    @Log(title="AI模型", businessType=BusinessType.UPDATE)
    @ApiOperation(value = "修改AI 模型")
    @PutMapping
    public AjaxResult edit(@RequestBody AiModel aiModel) {
        return this.toAjax(this.aiModelService.updateAiModel(aiModel));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:model:remove')")
    @Log(title="AI模型", businessType=BusinessType.DELETE)
    @ApiOperation(value = "删除AI 模型")
    @DeleteMapping(value={"/{ids}"})
    public AjaxResult remove(@PathVariable Long[] ids) {
        return this.toAjax(this.aiModelService.deleteAiModelByIds(ids));
    }

    @ApiOperation(value = "按 AI 服务查询模型下拉")
    @GetMapping(value={"/select/list/{id}"})
    public AjaxResult selectList(@PathVariable Long id) {
        return this.success(this.aiModelService.list(id));
    }
}
