package vip.mate.agent.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.agent.dto.BoxOfficeData;
import vip.mate.agent.dto.BoxOfficeMovie;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 猫眼专业版数据源。请求 piaofang.maoyan.com/i/api/dashboard-ajax,
 * 需带 signKey(MD5) 与 mygsig 反爬签名;
 * 该接口有 openresty WAF 风控,若被拦截(403)可在配置中补充浏览器登录后的 Cookie。
 */
@Slf4j
@Component
public class MaoyanBoxOfficeProvider implements BoxOfficeProvider {

    private static final String API_URL = "https://piaofang.maoyan.com/i/api/dashboard-ajax";
    private static final String REFERER = "https://piaofang.maoyan.com/dashboard";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    /** signKey 计算用的固定密钥(站点前端硬编码) */
    private static final String SIGN_KEY = "A013F70DB97834C0A5492378BD76C53A";
    /** mygsig.ms1 计算用的固定前缀(站点前端硬编码) */
    private static final String MGSIG_PREFIX = "581409236";
    private static final String CHANNEL_ID = "40009";
    private static final String S_VERSION = "2";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /** 可选:浏览器登录猫眼专业版后的完整 Cookie,用于通过风控鉴权 */
    @Value("${boxoffice.maoyan.cookie:}")
    private String cookie;

    @Override
    public String name() {
        return "maoyan";
    }

    @Override
    public BoxOfficeData getRealtimeBoxOffice() {
        BoxOfficeData.BoxOfficeDataBuilder builder = BoxOfficeData.builder()
                .totalBox("--").totalBoxUnit("万").updateInfo("--")
                .sourceName("猫眼专业版")
                .sourceUrl(REFERER)
                .list(Collections.emptyList());
        try {
            JsonNode movieList = fetchMovieList();
            // 新结构: movieList.data.list; 兼容旧结构 data.list
            JsonNode data = movieList.has("data") ? movieList.path("data") : movieList;
            JsonNode listNode = data.path("list");
            if (!listNode.isArray()) {
                return builder.build();
            }
            List<BoxOfficeMovie> list = new ArrayList<>();
            for (JsonNode item : listNode) {
                list.add(toMovie(item));
            }
            JsonNode updateInfo = data.path("updateInfo");
            return builder.totalBox(data.path("totalBox").asText("--"))
                    .totalBoxUnit(data.path("totalBoxUnit").asText("万"))
                    .updateInfo(updateInfo.isMissingNode() ? "--" : updateInfo.asText("--"))
                    .list(list)
                    .build();
        } catch (Exception e) {
            log.error("获取实时票房(maoyan)失败,可尝试在 boxoffice.maoyan.cookie 配置登录 Cookie", e);
            return builder.build();
        }
    }

    private JsonNode fetchMovieList() throws Exception {
        long ts = System.currentTimeMillis();
        String uaB64 = Base64.getEncoder().encodeToString(UA.getBytes(StandardCharsets.UTF_8));
        int index = ThreadLocalRandom.current().nextInt(1, 1000);
        String uuid = UUID.randomUUID().toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().replace("-", "");

        // signKey = MD5(method=GET&timeStamp=..&User-Agent=<b64>&index=..&channelId=..&sVersion=..&key=..)
        String signSource = "method=GET&timeStamp=" + ts
                + "&User-Agent=" + uaB64
                + "&index=" + index
                + "&channelId=" + CHANNEL_ID
                + "&sVersion=" + S_VERSION
                + "&key=" + SIGN_KEY;
        String signKey = md5(signSource.replaceAll("\\s+", " "));

        TreeMap<String, String> params = new TreeMap<>();
        params.put("User-Agent", uaB64);
        params.put("WuKongReady", "h5");
        params.put("channelId", CHANNEL_ID);
        params.put("index", String.valueOf(index));
        params.put("movieId", "");
        params.put("orderType", "0");
        params.put("sVersion", S_VERSION);
        params.put("signKey", signKey);
        params.put("timeStamp", String.valueOf(ts));
        params.put("uuid", uuid);
        String ms1 = md5(MGSIG_PREFIX + String.join("_", params.values()) + "$" + ts);
        String mygsig = "{\"m1\":\"0.0.3\",\"m2\":0,\"m3\":\"0.0.67_tool\",\"ms1\":\"" + ms1
                + "\",\"ts\":" + ts + ",\"ts1\":" + (ts - 100000) + "}";

        String query = "movieId=&orderType=0"
                + "&uuid=" + uuid
                + "&timeStamp=" + ts
                + "&User-Agent=" + URLEncoder.encode(uaB64, StandardCharsets.UTF_8)
                + "&index=" + index
                + "&channelId=" + CHANNEL_ID
                + "&sVersion=" + S_VERSION
                + "&signKey=" + signKey
                + "&WuKongReady=h5";

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(API_URL + "?" + query))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Accept", "application/json, text/plain, */*")
                .header("M-APPKEY", "fe_com.sankuai.movie.fe.ipro")
                .header("mygsig", mygsig)
                .GET();
        if (cookie != null && !cookie.isBlank()) {
            reqBuilder.header("Cookie", cookie);
        }
        HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());
        return root.path("movieList");
    }

    private BoxOfficeMovie toMovie(JsonNode item) {
        JsonNode info = item.path("movieInfo");
        return BoxOfficeMovie.builder()
                .movieName(pick(item, info, "movieName"))
                .movieId(pick(item, info, "movieId"))
                .boxInfo(pick(item, info, "boxInfo"))
                .boxRate(pick(item, info, "boxRate"))
                .sumBoxInfo(pick(item, info, "sumBoxInfo"))
                .releaseInfo(pick(item, info, "releaseInfo"))
                .showInfo(pick(item, info, "showInfo"))
                .showRate(pick(item, info, "showRate"))
                .avgSeatView(pick(item, info, "avgSeatView"))
                .avgShowView(pick(item, info, "avgShowView"))
                .avgViewBox(pick(item, info, "avgViewBox"))
                .inChart(true)
                .build();
    }

    /** 先从 item 取字段,取不到再尝试 movieInfo 子节点 */
    private static String pick(JsonNode item, JsonNode nested, String field) {
        String v = item.path(field).asText("");
        if (v.isEmpty() && nested != null) {
            v = nested.path(field).asText("");
        }
        return v.isEmpty() ? "--" : v;
    }

    private static String md5(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}