package com.ruoyi.webui.service.impl;

import com.ruoyi.webui.domain.SearchService;
import com.ruoyi.webui.mapper.SearchServiceMapper;
import com.ruoyi.webui.service.ISearchServiceService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SearchServiceServiceImpl implements ISearchServiceService {
    @Autowired
    private SearchServiceMapper searchServiceMapper;

    @Override
    public SearchService selectSearchServiceById(Long id) {
        return this.searchServiceMapper.selectSearchServiceById(id);
    }

    @Override
    public List<SearchService> selectSearchServiceList(SearchService searchService) {
        return this.searchServiceMapper.selectSearchServiceList(searchService);
    }

    @Override
    public int insertSearchService(SearchService searchService) {
        return this.searchServiceMapper.insertSearchService(searchService);
    }

    @Override
    public int updateSearchService(SearchService searchService) {
        return this.searchServiceMapper.updateSearchService(searchService);
    }

    @Override
    public int deleteSearchServiceByIds(Long[] ids) {
        return this.searchServiceMapper.deleteSearchServiceByIds(ids);
    }

    @Override
    public int deleteSearchServiceById(Long id) {
        return this.searchServiceMapper.deleteSearchServiceById(id);
    }

    @Override
    public List<SearchService> list() {
        return this.searchServiceMapper.selectList();
    }
}
