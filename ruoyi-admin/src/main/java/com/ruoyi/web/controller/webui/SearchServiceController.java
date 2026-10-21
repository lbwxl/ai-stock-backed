package com.ruoyi.web.controller.webui;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.webui.domain.SearchService;
import com.ruoyi.webui.service.ISearchServiceService;
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
@RequestMapping(value={"/webui/search"})
public class SearchServiceController extends BaseController {
    @Autowired
    private ISearchServiceService searchServiceService;

    @PreAuthorize(value="@ss.hasPermi('webui:service:list')")
    @GetMapping(value={"/list"})
    public TableDataInfo list(SearchService searchService) {
        this.startPage();
        List<SearchService> list = this.searchServiceService.selectSearchServiceList(searchService);
        return this.getDataTable(list);
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:export')")
    @Log(title="联网搜索服务", businessType=BusinessType.EXPORT)
    @PostMapping(value={"/export"})
    public void export(HttpServletResponse response, SearchService searchService) {
        List<SearchService> list = this.searchServiceService.selectSearchServiceList(searchService);
        ExcelUtil<SearchService> util = new ExcelUtil<SearchService>(SearchService.class);
        util.exportExcel(response, list, "联网搜索服务数据");
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:query')")
    @GetMapping(value={"/{id}"})
    public AjaxResult getInfo(@PathVariable(value="id") Long id) {
        return this.success(this.searchServiceService.selectSearchServiceById(id));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:add')")
    @Log(title="联网搜索服务", businessType=BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SearchService searchService) {
        return this.toAjax(this.searchServiceService.insertSearchService(searchService));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:edit')")
    @Log(title="联网搜索服务", businessType=BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SearchService searchService) {
        return this.toAjax(this.searchServiceService.updateSearchService(searchService));
    }

    @PreAuthorize(value="@ss.hasPermi('webui:service:remove')")
    @Log(title="联网搜索服务", businessType=BusinessType.DELETE)
    @DeleteMapping(value={"/{ids}"})
    public AjaxResult remove(@PathVariable Long[] ids) {
        return this.toAjax(this.searchServiceService.deleteSearchServiceByIds(ids));
    }

    @GetMapping(value={"/select/list"})
    public AjaxResult selectList() {
        return this.success(this.searchServiceService.list());
    }
}
