package vip.mate.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 票房榜单
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoxOfficeData {

    /** 今日总票房(数字) */
    private String totalBox;

    /** 今日总票房单位(万/亿) */
    private String totalBoxUnit;

    /** 数据更新时间描述 */
    private String updateInfo;

    /** 数据来源名称(猫眼专业版 / 公开源) */
    private String sourceName;

    /** 数据来源地址 */
    private String sourceUrl;

    /** 在映影片列表 */
    private List<BoxOfficeMovie> list;
}