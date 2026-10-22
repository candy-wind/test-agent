# 票房数据接口参考

## 数据源

### 主要数据源: apizero (极数本源)

- **接口地址**: `https://v1.apizero.cn/api/movie-box`
- **请求方式**: GET
- **鉴权**: 无需鉴权，匿名可调
- **数据底层来源**: 猫眼专业版
- **缓存周期**: 60 秒
- **说明**: 返回当日票房 Top10 在映影片

### 备选数据源: 猫眼专业版官方接口

- **接口地址**: `https://piaofang.maoyan.com/dashboard-ajax`
- **鉴权**: 需 signKey/mygsig 签名，可能被风控拦截
- **可配置**: 登录 Cookie (`boxoffice.maoyan.cookie`)
- **说明**: 数据最全最准，但风控严格

## apizero 返回数据结构

```json
{
  "code": 0,
  "msg": "成功",
  "data": {
    "update_time": "2026-09-30 09:13:48",
    "total": 10,
    "list": [
      {
        "rank": 1,
        "name": "影片名称",
        "release_days": "上映51天",
        "total_box": "22.97亿",
        "box_office": 88.16,
        "box_rate": 14.4,
        "show_rate": 18.3,
        "seat_rate": 0.3
      }
    ]
  },
  "tips": "极数本源 · https://apizero.cn",
  "request_id": "xxx"
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `rank` | int | 排名 |
| `name` | string | 影片名称 |
| `release_days` | string | 上映信息（"上映首日"/"上映N天"/"点映"） |
| `total_box` | string | 累计票房（含单位，如"22.97亿"） |
| `box_office` | float | 当日实时票房（单位: 万） |
| `box_rate` | float | 当日票房占比（%） |
| `show_rate` | float | 排片占比（%） |
| `seat_rate` | float | 场均上座率（%） |

## 项目内 API 接口

### 获取票房榜单

```
GET /api/boxoffice/list
```

返回 `BoxOfficeData` 对象，包含:
- `totalBox`: 今日总票房
- `totalBoxUnit`: 单位（万/亿）
- `updateInfo`: 数据更新时间
- `sourceName`: 数据来源名称
- `sourceUrl`: 数据来源地址
- `list`: 在映影片列表 (`BoxOfficeMovie[]`)

### 搜索影片

```
GET /api/boxoffice/search?keyword=关键词
```

返回匹配的在映影片列表。

## 项目配置

```yaml
boxoffice:
  provider: apizero          # 数据源: apizero | maoyan
  refresh-interval-ms: 60000 # 定时刷新缓存间隔
  maoyan:
    cookie: ""               # 猫眼登录 Cookie (可选)
```

## 相关代码位置

| 文件 | 说明 |
|------|------|
| `service/BoxOfficeService.java` | 票房服务接口 |
| `service/impl/BoxOfficeServiceImpl.java` | 票房服务实现（定时缓存） |
| `service/provider/BoxOfficeProvider.java` | 数据源抽象接口 |
| `service/provider/ApizeroBoxOfficeProvider.java` | apizero 数据源实现 |
| `service/provider/MaoyanBoxOfficeProvider.java` | 猫眼数据源实现 |
| `dto/BoxOfficeData.java` | 票房榜单 DTO |
| `dto/BoxOfficeMovie.java` | 单部影片 DTO |
| `controller/BoxOfficeController.java` | 票房 REST 接口 |