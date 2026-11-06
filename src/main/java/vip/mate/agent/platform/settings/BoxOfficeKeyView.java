package vip.mate.agent.platform.settings;

/**
 * 外部数据源密钥展示信息（不返回明文）。
 *
 * @param configured 是否已配置
 * @param masked     掩码后的 Key
 * @param source     配置来源：database / environment / none
 */
public record BoxOfficeKeyView(boolean configured, String masked, String source) {
}
