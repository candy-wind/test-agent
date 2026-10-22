package vip.mate.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /**
     * 回复内容
     */
    private String content;

    /**
     * 使用的模型
     */
    private String model;

    /**
     * 搜索结果（如果启用了搜索）
     */
    private List<SearchResult> searchResults;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResult {
        private String title;
        private String url;
        private String snippet;
    }
}
