package vip.mate.agent.service;

import vip.mate.agent.dto.BoxOfficeData;
import vip.mate.agent.dto.BoxOfficeMovie;

import java.util.List;

/**
 * 票房服务
 */
public interface BoxOfficeService {

    /**
     * 获取实时票房榜单(全部在映影片)
     */
    BoxOfficeData getRealtimeBoxOffice();

    /**
     * 全库搜索影片:
     *  - 命中在映影片 -> 返回带实时票房数据
     *  - 未在映      -> 返回影片基础信息(无票房字段)
     */
    List<BoxOfficeMovie> searchMovies(String keyword);
}