package com.ruoyi.webui.domain.vo;

import com.ruoyi.webui.domain.vo.AiServiceVo;
import com.ruoyi.webui.domain.vo.SearchServiceVo;

public class SysLoginVo {
    private Long userId;
    private String username;
    private AiServiceVo aiService;
    private SearchServiceVo searchService;

    public Long getUserId() {
        return this.userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public AiServiceVo getAiService() {
        return this.aiService;
    }

    public void setAiService(AiServiceVo aiService) {
        this.aiService = aiService;
    }

    public SearchServiceVo getSearchService() {
        return this.searchService;
    }

    public void setSearchService(SearchServiceVo searchService) {
        this.searchService = searchService;
    }
}
