---
name: box-office
description: 获取中国电影实时票房数据。当用户询问今日票房、电影排片、上座率、影片累计票房，或需要搜索在映影片时触发此技能。支持通过项目 API 或直接调用 apizero 公开接口获取猫眼专业版票房数据。
---

# Box Office

## Overview

获取中国电影实时票房数据的技能，数据底层来源于猫眼专业版，通过 apizero 公开接口免鉴权获取。支持查看当日票房榜单、搜索在映影片、查询排片/上座率等指标。

## Quick Start

### 1. 获取实时票房榜单

直接执行脚本获取票房 Top10:

```bash
python scripts/fetch_boxoffice.py
```

输出示例:

```
更新时间: 2026-09-30 09:13:48
今日总票房: 508.5万
在映影片: 10部
------------------------------------------------------------
  1. 复仇者联盟4：终局之战
     当日票房: 160.49万 (占比26.2%)  累计: 43.53亿
     排片: 17.3%  上座率: 0.3%  上映首日
  2. 欢迎来龙餐馆
     当日票房: 88.16万 (占比14.4%)  累计: 22.97亿
     排片: 18.3%  上座率: 0.3%  上映51天
...
```

### 2. 搜索影片

```bash
python scripts/fetch_boxoffice.py --search "影片名"
```

### 3. JSON 格式输出

```bash
python scripts/fetch_boxoffice.py --json
```

### 4. 通过项目 API 获取

若 test-agent 服务正在运行 (端口 8080)，调用项目接口:

```bash
# 票房榜单
curl http://localhost:8080/api/boxoffice/list

# 搜索影片
curl "http://localhost:8080/api/boxoffice/search?keyword=复仇者"
```

## Fields

| 字段 | 说明 |
|------|------|
| `box_office` | 当日实时票房（单位: 万） |
| `total_box` | 累计票房（含单位，如"22.97亿"） |
| `box_rate` | 当日票房占比（%） |
| `show_rate` | 排片占比（%） |
| `seat_rate` | 场均上座率（%） |
| `release_days` | 上映信息（"上映首日"/"上映N天"/"点映"） |

## References

详细的接口文档、数据结构、配置说明和代码位置见 `references/boxoffice_api.md`。