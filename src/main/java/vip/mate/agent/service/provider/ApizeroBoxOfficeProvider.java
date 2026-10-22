package vip.mate.agent.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.agent.dto.BoxOfficeData;
import vip.mate.agent.dto.BoxOfficeMovie;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 公开票房数据源:极数本源 movie-box(数据来源猫眼专业版,60 秒缓存,匿名可调)。
 * 返回当日票房 Top10,字段:name/total_box/box_office/box_rate/show_rate/seat_rate/release_days。
 */
@Slf4j
@Component
public class ApizeroBoxOfficeProvider implements BoxOfficeProvider {

    private static final String API_URL = "https://v1.apizero.cn/api/movie-box";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "apizero";
    }

    @Override
    public BoxOfficeData getRealtimeBoxOffice() {
        BoxOfficeData.BoxOfficeDataBuilder builder = BoxOfficeData.builder()
                .totalBox("--").totalBoxUnit("万").updateInfo("--")
                .sourceName("极数本源公开接口")
                .sourceUrl(API_URL)
                .list(Collections.emptyList());
        try {
            JsonNode data = fetchData();
            JsonNode listNode = data.path("list");
            if (!listNode.isArray()) {
                return builder.build();
            }
            List<BoxOfficeMovie> list = new ArrayList<>();
            double daySum = 0;
            for (JsonNode item : listNode) {
                list.add(toMovie(item));
                daySum += item.path("box_office").asDouble(0);
            }
            return builder.totalBox(String.format("%.1f", daySum))
                    .totalBoxUnit("万")
                    .updateInfo(data.path("update_time").asText("--"))
                    .list(list)
                    .build();
        } catch (Exception e) {
            log.error("获取实时票房(apizero)失败", e);
            return builder.build();
        }
    }

    private JsonNode fetchData() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", UA)
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body()).path("data");
    }

    private BoxOfficeMovie toMovie(JsonNode item) {
        return BoxOfficeMovie.builder()
                .movieName(item.path("name").asText(""))
                .boxInfo(num(item, "box_office"))
                .boxRate(pct(item, "box_rate"))
                .sumBoxInfo(item.path("total_box").asText("--"))
                .releaseInfo(item.path("release_days").asText("--"))
                .showRate(pct(item, "show_rate"))
                .avgSeatView(pct(item, "seat_rate"))
                .avgShowView("--")
                .avgViewBox("--")
                .inChart(true)
                .build();
    }

    /** 数值字段,缺失返回 -- */
    private static String num(JsonNode item, String field) {
        JsonNode n = item.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return "--";
        }
        String s = n.asText();
        return s.isEmpty() ? "--" : s;
    }

    /** 百分比字段,统一补 % 号 */
    private static String pct(JsonNode item, String field) {
        String s = num(item, field);
        return "--".equals(s) ? s : s + "%";
    }
}