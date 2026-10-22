package vip.mate.agent.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.agent.dto.ChatResponse;
import vip.mate.agent.service.SearchService;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索服务实现：使用 DuckDuckGo HTML 搜索（无需 API Key）
 */
@Slf4j
@Service
public class SearchServiceImpl implements SearchService {

    private static final String SEARCH_URL = "https://html.duckduckgo.com/html/?q=";

    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "<a rel=\"nofollow\" class=\"result__a\" href=\"([^\"]+)\">(.*?)</a>"
            + ".*?<a class=\"result__snippet\"[^>]*>(.*?)</a>",
            Pattern.DOTALL
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Override
    public List<ChatResponse.SearchResult> search(String query, int maxResults) {
        List<ChatResponse.SearchResult> results = new ArrayList<>();
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SEARCH_URL + encodedQuery))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String html = response.body();

            Matcher matcher = RESULT_PATTERN.matcher(html);
            while (matcher.find() && results.size() < maxResults) {
                String url = decodeHtml(matcher.group(1));
                String title = stripTags(matcher.group(2));
                String snippet = stripTags(matcher.group(3));
                results.add(ChatResponse.SearchResult.builder()
                        .url(url)
                        .title(title)
                        .snippet(snippet)
                        .build());
            }
        } catch (Exception e) {
            log.error("搜索失败: query={}", query, e);
        }
        return results;
    }

    private String stripTags(String text) {
        return text.replaceAll("<[^>]+>", "").trim();
    }

    private String decodeHtml(String text) {
        return text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim();
    }
}
