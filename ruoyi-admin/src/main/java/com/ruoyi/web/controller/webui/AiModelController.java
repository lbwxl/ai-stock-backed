package com.ruoyi.web.controller.webui;

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

@RestController
@RequestMapping(value={"/webui/model"})
public class AiModelController extends BaseController {
    @Autowired
    private IAiModelService aiModelService;

    @PreAuthorize(value="@ss.hasPermi('webui:model:list')")
    @GetMapping(value={"/list"})
    public TableDataInfo list(AiModel aiModel) {
        this.startPage();
        List<AiModelVo> list = this.aiModelService.selectAiModelList(aiModel);
        return this.getDataTable(list);
    }

    @PreAuthorize(value="@ss.hasPermi('webui:model:export')")
    @Log(title="AI模型", businessType=BusinessType.EXPORT)
    @PostMapping(value={"/export"})
    public void export(HttpServletResponse response, AiModel aiModel) {
        List<AiModelVo> list = this.aiModelService.selectAiModelList(aiModel);
        ExcelUtil<AiModelVo> util = new ExcelUtil<AiModelVo>(AiModelVo.class);
        util.exportExcel(response, list, "AI模型数据");
    }

    @PreAuthorize(value="@ss.hasPermi('webui:model:query')")
    @GetMapping(value={"/{id}"})
    public AjaxResult getInfo(@PathVariable(value="id") Long id) {
        return this.success(this.aiModelService.selectAiModelById(id));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:model:add')")
    @Log(title="AI模型", businessType=BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AiModel aiModel) {
        return this.toAjax(this.aiModelService.insertAiModel(aiModel));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:model:edit')")
    @Log(title="AI模型", businessType=BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AiModel aiModel) {
        return this.toAjax(this.aiModelService.updateAiModel(aiModel));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:model:remove')")
    @Log(title="AI模型", businessType=BusinessType.DELETE)
    @DeleteMapping(value={"/{ids}"})
    public AjaxResult remove(@PathVariable Long[] ids) {
        return this.toAjax(this.aiModelService.deleteAiModelByIds(ids));
    }

    @GetMapping(value={"/select/list/{id}"})
    public AjaxResult selectList(@PathVariable Long id) {
        return this.success(this.aiModelService.list(id));
    }
}
