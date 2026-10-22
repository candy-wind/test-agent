#!/usr/bin/env python3
"""获取实时票房数据脚本。

用法:
  python fetch_boxoffice.py                  # 获取实时票房榜单
  python fetch_boxoffice.py --search 关键词   # 搜索影片
  python fetch_boxoffice.py --json            # 以 JSON 格式输出

数据来源: 极数本源公开接口 (https://v1.apizero.cn/api/movie-box)
数据底层来源: 猫眼专业版
"""

import argparse
import json
import sys
import urllib.request
import urllib.error

API_URL = "https://v1.apizero.cn/api/movie-box"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
)


def fetch_data():
    """从 apizero 拉取票房数据。"""
    req = urllib.request.Request(API_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = resp.read().decode("utf-8")
    return json.loads(body)


def format_movie(rank, movie):
    """格式化单部影片信息为文本行。"""
    name = movie.get("name", "--")
    total_box = movie.get("total_box", "--")
    box_office = movie.get("box_office", "--")
    box_rate = movie.get("box_rate", "--")
    show_rate = movie.get("show_rate", "--")
    seat_rate = movie.get("seat_rate", "--")
    release_days = movie.get("release_days", "--")
    return (
        f"  {rank}. {name}\n"
        f"     当日票房: {box_office}万 (占比{box_rate}%)  累计: {total_box}\n"
        f"     排片: {show_rate}%  上座率: {seat_rate}%  {release_days}"
    )


def print_list(data):
    """文本格式输出票房榜单。"""
    d = data.get("data", {})
    update_time = d.get("update_time", "--")
    movies = d.get("list", [])

    day_sum = sum(m.get("box_office", 0) for m in movies)
    print(f"更新时间: {update_time}")
    print(f"今日总票房: {day_sum:.1f}万")
    print(f"在映影片: {len(movies)}部")
    print("-" * 60)
    for i, m in enumerate(movies, 1):
        print(format_movie(i, m))


def print_search(data, keyword):
    """文本格式输出搜索结果。"""
    d = data.get("data", {})
    movies = d.get("list", [])
    keyword_lower = keyword.lower()
    matched = [m for m in movies if keyword_lower in m.get("name", "").lower()]

    if not matched:
        print(f"未找到匹配「{keyword}」的在映影片")
        return

    print(f"搜索「{keyword}」结果 ({len(matched)}部):")
    print("-" * 60)
    for i, m in enumerate(matched, 1):
        print(format_movie(i, m))


def main():
    parser = argparse.ArgumentParser(description="获取实时票房数据")
    parser.add_argument("--search", "-s", metavar="KEYWORD", help="搜索影片名称")
    parser.add_argument("--json", action="store_true", help="以 JSON 格式输出")
    args = parser.parse_args()

    try:
        data = fetch_data()
    except urllib.error.URLError as e:
        print(f"获取票房数据失败: {e}", file=sys.stderr)
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"解析票房数据失败: {e}", file=sys.stderr)
        sys.exit(1)

    if args.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
    elif args.search:
        print_search(data, args.search)
    else:
        print_list(data)


if __name__ == "__main__":
    main()