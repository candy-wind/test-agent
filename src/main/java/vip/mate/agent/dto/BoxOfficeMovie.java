package vip.mate.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 票房数据(对应猫眼 second.json 中的影片字段,原样透传)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoxOfficeMovie {

    /** 电影名 */
    private String movieName;

    /** 影片ID(猫眼 movieId,用于详情) */
    private String movieId;

    /** 当日实时票房(万) */
    private String boxInfo;

    /** 当日票房占比 */
    private String boxRate;

    /** 累计票房 */
    private String sumBoxInfo;

    /** 上映信息(上映天数) */
    private String releaseInfo;

    /** 排片场次 */
    private String showInfo;

    /** 排片占比 */
    private String showRate;

    /** 场均上座率 */
    private String avgSeatView;

    /** 场均人次 */
    private String avgShowView;

    /** 平均票价 */
    private String avgViewBox;

    /** 是否在映(参与实时票房排行) */
    @Builder.Default
    private boolean inChart = true;
}