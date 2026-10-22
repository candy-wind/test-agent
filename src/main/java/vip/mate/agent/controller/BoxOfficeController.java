package vip.mate.agent.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.agent.dto.BoxOfficeData;
import vip.mate.agent.dto.BoxOfficeMovie;
import vip.mate.agent.service.BoxOfficeService;

import java.util.List;
import java.util.Map;

/**
 * 票房接口(鉴权与 chat 一致,AuthFilter 自动拦截 /api/* )
 */
@Slf4j
@RestController
@RequestMapping("/api/boxoffice")
@RequiredArgsConstructor
public class BoxOfficeController {

    private final BoxOfficeService boxOfficeService;

    /**
     * 实时票房榜单(在映影片)
     */
    @GetMapping("/list")
    public BoxOfficeData list() {
        log.info("[boxoffice/list]");
        return boxOfficeService.getRealtimeBoxOffice();
    }

    /**
     * 全库搜索影片:命中在映带实时票房,未在映返回基础信息
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String keyword) {
        log.info("[boxoffice/search] keyword={}", keyword);
        List<BoxOfficeMovie> movies = boxOfficeService.searchMovies(keyword);
        return Map.of("success", true, "list", movies);
    }
}