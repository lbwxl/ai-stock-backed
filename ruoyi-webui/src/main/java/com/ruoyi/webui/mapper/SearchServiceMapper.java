package com.ruoyi.webui.mapper;

import com.ruoyi.webui.domain.SearchService;
import java.util.List;

public interface SearchServiceMapper {
    public SearchService selectSearchServiceById(Long id);

    public List<SearchService> selectSearchServiceList(SearchService searchService);

    public int insertSearchService(SearchService searchService);

    public int updateSearchService(SearchService searchService);

    public int deleteSearchServiceById(Long id);

    public int deleteSearchServiceByIds(Long[] ids);

    public List<SearchService> selectList();
}
