package vip.mate.agent.service.provider;

import vip.mate.agent.dto.BoxOfficeData;

/**
 * 票房数据源抽象。通过配置 boxoffice.provider 选择具体实现。
 */
public interface BoxOfficeProvider {

    /**
     * 数据源标识,与 boxoffice.provider 配置值对应
     */
    String name();

    /**
     * 拉取实时票房榜单
     */
    BoxOfficeData getRealtimeBoxOffice();
}