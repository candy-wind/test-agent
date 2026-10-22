package vip.mate.agent.service;

import vip.mate.agent.dto.ChatResponse;

import java.util.List;

/**
 * 搜索服务
 */
public interface SearchService {

    /**
     * 执行搜索，返回结果列表
     */
    List<ChatResponse.SearchResult> search(String query, int maxResults);
}
