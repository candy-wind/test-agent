package vip.mate.agent.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.agent.dto.BoxOfficeData;
import vip.mate.agent.dto.BoxOfficeMovie;
import vip.mate.agent.service.BoxOfficeService;
import vip.mate.agent.service.provider.BoxOfficeProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 票房服务:按 boxoffice.provider 配置选择数据源(默认 apizero 公开源),
 * 由定时任务周期刷新并缓存,页面请求直接读缓存。
 * 可选值: apizero(免登录) | maoyan(猫眼专业版,需签名/登录态)
 */
@Slf4j
@Service
public class BoxOfficeServiceImpl implements BoxOfficeService {

    private final BoxOfficeProvider provider;

    /** 最新一次拉取结果(定时任务写入,页面读取) */
    private volatile BoxOfficeData cache;

    public BoxOfficeServiceImpl(List<BoxOfficeProvider> providers,
                                @Value("${boxoffice.provider:apizero}") String providerName) {
        this.provider = providers.stream()
                .filter(p -> p.name().equalsIgnoreCase(providerName))
                .findFirst()
                .orElseGet(() -> providers.get(0));
        log.info("[boxoffice] 当前数据源: {} (配置 boxoffice.provider={})", provider.name(), providerName);
    }

    @Override
    public BoxOfficeData getRealtimeBoxOffice() {
        BoxOfficeData current = cache;
        // 首次请求时定时任务可能尚未完成,兜底同步拉取一次
        return current != null ? current : refreshCache();
    }

    @Override
    public List<BoxOfficeMovie> searchMovies(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        String kw = keyword.trim().toLowerCase();
        List<BoxOfficeMovie> results = new ArrayList<>();
        for (BoxOfficeMovie m : getRealtimeBoxOffice().getList()) {
            if (m.getMovieName() != null && m.getMovieName().toLowerCase().contains(kw)) {
                results.add(m);
            }
        }
        return results;
    }

    /**
     * 定时刷新数据源缓存(默认 60 秒一次),失败保留上次结果
     */
    @Scheduled(initialDelayString = "${boxoffice.refresh-initial-delay-ms:0}",
            fixedDelayString = "${boxoffice.refresh-interval-ms:60000}")
    public BoxOfficeData refreshCache() {
        try {
            BoxOfficeData data = provider.getRealtimeBoxOffice();
            this.cache = data;
            log.info("[boxoffice] 定时刷新完成: 数据源={}, 来源={}, 影片数={}, 更新时间={}",
                    provider.name(), data.getSourceName(),
                    data.getList() == null ? 0 : data.getList().size(), data.getUpdateInfo());
            return data;
        } catch (Exception e) {
            log.error("[boxoffice] 定时刷新失败,保留上次缓存", e);
            return cache;
        }
    }
}