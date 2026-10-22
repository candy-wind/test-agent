package vip.mate.agent.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.agent.dto.BoxOfficeData;
import vip.mate.agent.dto.BoxOfficeMovie;
import vip.mate.agent.service.BoxOfficeService;

import java.util.List;

/**
 * Agent 工具注册表：定义可供 LLM 调用的工具并执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final BoxOfficeService boxOfficeService;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构建发送给 LLM 的 tools 定义（OpenAI Function Calling 格式）
     */
    public String buildToolsJson() {
        try {
            ObjectNode root = mapper.createObjectNode();
            ArrayNode toolsArray = root.putArray("tools");

            // 工具 1: 获取实时票房榜单
            ObjectNode listTool = toolsArray.addObject();
            listTool.put("type", "function");
            ObjectNode listFn = listTool.putObject("function");
            listFn.put("name", "get_box_office_list");
            listFn.put("description", "获取今日中国电影实时票房排行榜（完整榜单Top10），无需提供影片名称，直接返回所有在映影片的排名、当日票房、累计票房、排片占比、上座率等数据。当用户询问'今日票房'、'票房排行榜'、'票房榜'、'哪些电影在映'、'今天有什么电影'等榜单类问题时使用此工具。数据来源猫眼专业版。");
            listFn.putObject("parameters").put("type", "object")
                    .putObject("properties")
                    .putObject("description").put("type", "string").put("description", "可选：指定查询天数，如'今日'、'昨天'，留空默认查今日");
            listFn.putArray("required");

            // 工具 2: 搜索影片
            ObjectNode searchTool = toolsArray.addObject();
            searchTool.put("type", "function");
            ObjectNode searchFn = searchTool.putObject("function");
            searchFn.put("name", "search_movie");
            searchFn.put("description", "按影片名称关键词搜索在映影片，返回匹配影片的实时票房和上映信息。当用户提供了具体的电影名称或部分名称时使用此工具。注意：如果用户想要看完整排行榜而不是搜索特定影片，请使用 get_box_office_list 工具。");
            ObjectNode searchProps = searchFn.putObject("parameters")
                    .put("type", "object")
                    .putObject("properties");
            searchProps.putObject("keyword").put("type", "string").put("description", "影片名称关键词");
            searchFn.putArray("required").add("keyword");

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("构建工具定义失败", e);
        }
    }

    /**
     * 执行工具调用，返回 JSON 字符串结果
     */
    public String executeTool(String name, String arguments) {
        log.info("[tool-call] name={} args={}", name, arguments);
        try {
            JsonNode args = arguments != null && !arguments.isBlank()
                    ? mapper.readTree(arguments) : mapper.createObjectNode();

            return switch (name) {
                case "get_box_office_list" -> executeGetBoxOfficeList(args);
                case "search_movie" -> executeSearchMovie(args);
                default -> mapper.writeValueAsString(mapper.createObjectNode()
                        .put("error", "未知工具: " + name));
            };
        } catch (Exception e) {
            log.error("[tool-call] 执行失败: name={}", name, e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 工具: get_box_office_list
     */
    private String executeGetBoxOfficeList(JsonNode args) throws Exception {
        BoxOfficeData data = boxOfficeService.getRealtimeBoxOffice();
        ObjectNode result = mapper.createObjectNode();
        result.put("updateTime", data.getUpdateInfo());
        result.put("totalBox", data.getTotalBox() + data.getTotalBoxUnit());
        result.put("source", data.getSourceName());

        var arr = result.putArray("movies");
        List<BoxOfficeMovie> list = data.getList();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                BoxOfficeMovie m = list.get(i);
                var item = arr.addObject();
                item.put("rank", i + 1);
                item.put("name", m.getMovieName());
                item.put("dayBoxOffice", m.getBoxInfo() + "万");
                item.put("totalBox", m.getSumBoxInfo());
                item.put("boxRate", m.getBoxRate());
                item.put("showRate", m.getShowRate());
                item.put("seatRate", m.getAvgSeatView());
                item.put("releaseDays", m.getReleaseInfo());
            }
        }
        return mapper.writeValueAsString(result);
    }

    /**
     * 工具: search_movie
     */
    String executeSearchMovie(JsonNode args) throws Exception {
        String keyword = args.path("keyword").asText("");
        if (keyword.isBlank()) {
            return "{\"error\":\"keyword 不能为空\"}";
        }
        List<BoxOfficeMovie> movies = boxOfficeService.searchMovies(keyword);
        ObjectNode result = mapper.createObjectNode();
        result.put("keyword", keyword);
        result.put("count", movies.size());
        var arr = result.putArray("movies");
        for (BoxOfficeMovie m : movies) {
            var item = arr.addObject();
            item.put("name", m.getMovieName());
            item.put("dayBoxOffice", m.getBoxInfo() + "万");
            item.put("totalBox", m.getSumBoxInfo());
            item.put("boxRate", m.getBoxRate());
            item.put("showRate", m.getShowRate());
            item.put("seatRate", m.getAvgSeatView());
            item.put("releaseDays", m.getReleaseInfo());
        }
        return mapper.writeValueAsString(result);
    }
}