#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端到端验证脚本：用与 APP 完全相同的策略，真实解析抖音图文分享链接并下载图集。

策略（与 core/PublicPageParser.fetchForParsing 一致）：
  1. 常规 UA 请求短链，跟随 302 拿到带分享参数的完整地址
  2. 用搜索引擎爬虫 UA 请求该地址，命中平台为 SEO 输出的服务端渲染页
  3. SSR 是概率性返回，最多重试 5 次
  4. 解析 <script type="application/ld+json"> 的 schema.org article
  5. 下载 image 数组里的每张图，校验为可读图片并落盘

不涉及任何签名算法、鉴权接口或登录态；只读公开页面自身声明的结构化数据。

用法: python3 verify/verify_live.py
输出: verify_output/ 下的图片、拼图与 report.json
"""
import json
import os
import re
import sys
import urllib.request

MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
)
SEO_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
ATTEMPTS = 5

SHARES = [
    ("作品1", "https://v.douyin.com/KOni4tR0sQQ/"),
    ("作品2", "https://v.douyin.com/nb2psOMhb3U/"),
]

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "verify_output"))
LD_BLOCK = re.compile(
    r"<script[^>]*application/ld\+json[^>]*>(.*?)</script>", re.S | re.I
)
MEDIA_TYPES = {"article", "videoobject", "imageobject", "audioobject", "creativework"}


def fetch(url, ua):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": ua,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "zh-CN,zh;q=0.9",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, r.geturl(), r.read().decode("utf-8", "replace")


def parse_ld(html):
    for block in LD_BLOCK.findall(html):
        try:
            data = json.loads(block)
        except (ValueError, TypeError):
            continue
        if str(data.get("@type", "")).lower() not in MEDIA_TYPES:
            continue
        images = data.get("image") or []
        if isinstance(images, str):
            images = [images]
        author = data.get("author") or {}
        name = author.get("name", "") if isinstance(author, dict) else ""
        if not images:
            continue
        return {
            "type": data.get("@type"),
            "title": data.get("headline") or data.get("name") or "",
            "caption": data.get("articleBody") or data.get("description") or "",
            "author": name,
            "published": data.get("datePublished", ""),
            "imageUrls": images,
        }
    return None


def download(url, path, ua):
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    with urllib.request.urlopen(req, timeout=40) as r:
        content_type = r.headers.get("Content-Type", "")
        data = r.read()
    with open(path, "wb") as f:
        f.write(data)
    return content_type, len(data)


def make_contact_sheet(paths, out_path, thumb_w=360):
    from PIL import Image, ImageOps, ImageDraw

    thumbs = []
    for p in paths:
        im = Image.open(p).convert("RGB")
        w = thumb_w
        h = max(1, int(im.height * w / im.width))
        thumbs.append(ImageOps.fit(im, (w, min(h, 480))))
    if not thumbs:
        return False
    cols = 2
    cell_h = max(t.height for t in thumbs) + 28
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * thumb_w, rows * cell_h), (245, 246, 250))
    draw = ImageDraw.Draw(sheet)
    for i, t in enumerate(thumbs):
        x = (i % cols) * thumb_w
        y = (i // cols) * cell_h
        sheet.paste(t, (x, y))
        draw.text((x + 8, y + t.height + 6), f"图 {i + 1}", fill=(40, 44, 60))
    sheet.save(out_path, "JPEG", quality=88)
    return True


def main():
    os.makedirs(OUT, exist_ok=True)
    report = {"strategy": "常规UA跟随302 → 爬虫UA触发SSR → JSON-LD解析 → 图片下载校验",
              "attempts_per_link": ATTEMPTS, "results": []}
    ok = True

    for tag, short in SHARES:
        entry = {"tag": tag, "shortUrl": short, "ok": False}
        print(f"\n=== {tag}: {short} ===")
        final = short
        parsed = None
        used = 0

        for k in range(ATTEMPTS):
            ua = MOBILE_UA if k == 0 else SEO_UA
            url = short if k == 0 else final
            used += 1
            try:
                code, final_url, html = fetch(url, ua)
            except Exception as e:
                print(f"  第{k+1}次请求失败: {e}")
                continue
            if final_url:
                final = final_url
            candidate = parse_ld(html)
            print(f"  第{k+1}次: HTTP {code}, {len(html)} 字节, "
                  f"UA={'常规' if k == 0 else '爬虫'}, "
                  f"命中图片 {len(candidate['imageUrls']) if candidate else 0} 张")
            if candidate and candidate["imageUrls"]:
                parsed = candidate
                break

        if not parsed:
            entry["error"] = f"重试 {used} 次仍未命中 SSR 结构化数据"
            print(f"  [失败] {entry['error']}")
            ok = False
            report["results"].append(entry)
            continue

        entry.update({
            "finalUrl": final,
            "attemptsUsed": used,
            "title": parsed["title"],
            "author": parsed["author"],
            "published": parsed["published"],
            "imageCount": len(parsed["imageUrls"]),
            "files": [],
        })
        print(f"  标题: {parsed['title']}")
        print(f"  作者: {parsed['author']}  发布: {parsed['published']}")
        print(f"  图集: {len(parsed['imageUrls'])} 张")

        paths = []
        for i, u in enumerate(parsed["imageUrls"], 1):
            name = f"{tag}_图{i}.webp"
            path = os.path.join(OUT, name)
            try:
                ctype, size = download(u, path, MOBILE_UA)
                from PIL import Image
                with Image.open(path) as im:
                    dim = f"{im.width}x{im.height}"
                    im.verify()
                paths.append(path)
                entry["files"].append({"name": name, "bytes": size, "contentType": ctype, "dimensions": dim})
                print(f"    图{i}: HTTP 200 / {ctype} / {size} 字节 / {dim}  [可读]")
            except Exception as e:
                entry["files"].append({"name": name, "error": str(e)})
                print(f"    图{i} 下载或校验失败: {e}")
                ok = False

        if paths:
            sheet = os.path.join(OUT, f"{tag}_拼图.jpg")
            if make_contact_sheet(paths, sheet):
                entry["contactSheet"] = os.path.basename(sheet)
                print(f"  预览拼图: {sheet}")
        entry["ok"] = len(paths) == len(parsed["imageUrls"])
        ok = ok and entry["ok"]
        report["results"].append(entry)

    report["allPassed"] = ok
    with open(os.path.join(OUT, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("\n=== 总结 ===")
    for r in report["results"]:
        print(f"  {r['tag']}: {'通过' if r.get('ok') else '失败'} "
              f"- {r.get('title','')} / {r.get('imageCount',0)} 张")
    print(f"  总体: {'全部通过' if ok else '存在失败'}; 报告: {os.path.join(OUT, 'report.json')}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
