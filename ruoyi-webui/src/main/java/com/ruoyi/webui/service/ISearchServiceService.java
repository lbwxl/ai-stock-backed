package com.ruoyi.webui.service;

import com.ruoyi.webui.domain.SearchService;
import java.util.List;

public interface ISearchServiceService {
    public SearchService selectSearchServiceById(Long id);

    public List<SearchService> selectSearchServiceList(SearchService searchService);

    public int insertSearchService(SearchService searchService);

    public int updateSearchService(SearchService searchService);

    public int deleteSearchServiceByIds(Long[] ids);

    public int deleteSearchServiceById(Long id);

    public List<SearchService> list();
}
