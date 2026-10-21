package com.ruoyi.web.controller.webui;

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

@RestController
@RequestMapping(value={"/webui/service"})
public class AiServiceController extends BaseController {
    @Autowired
    private IAiServiceService aiServiceService;

    @PreAuthorize(value="@ss.hasPermi('webui:service:list')")
    @GetMapping(value={"/list"})
    public TableDataInfo list(AiService aiService) {
        this.startPage();
        List<AiService> list = this.aiServiceService.selectAiServiceList(aiService);
        return this.getDataTable(list);
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:export')")
    @Log(title="AI服务", businessType=BusinessType.EXPORT)
    @PostMapping(value={"/export"})
    public void export(HttpServletResponse response, AiService aiService) {
        List<AiService> list = this.aiServiceService.selectAiServiceList(aiService);
        ExcelUtil<AiService> util = new ExcelUtil<AiService>(AiService.class);
        util.exportExcel(response, list, "AI服务数据");
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:query')")
    @GetMapping(value={"/{id}"})
    public AjaxResult getInfo(@PathVariable(value="id") Long id) {
        return this.success(this.aiServiceService.selectAiServiceById(id));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:add')")
    @Log(title="AI服务", businessType=BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AiService aiService) {
        return this.toAjax(this.aiServiceService.insertAiService(aiService));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:edit')")
    @Log(title="AI服务", businessType=BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AiService aiService) {
        return this.toAjax(this.aiServiceService.updateAiService(aiService));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:remove')")
    @Log(title="AI服务", businessType=BusinessType.DELETE)
    @DeleteMapping(value={"/{ids}"})
    public AjaxResult remove(@PathVariable Long[] ids) {
        return this.toAjax(this.aiServiceService.deleteAiServiceByIds(ids));
    }

    @GetMapping(value={"/select/list"})
    public AjaxResult selectList() {
        return this.success(this.aiServiceService.list());
    }
}
