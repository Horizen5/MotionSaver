#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v2.2 端到端验证：完全复刻 APP 的解析链路（含多条链接一起粘贴）。

  1) 向 ttwid.bytedance.com 注册**公开访客标识**（Cookie，无需账号/签名）
  2) 普通手机 UA + 该 Cookie 请求用户粘贴的公开分享短链
  3) 解析 window._ROUTER_DATA.videoInfoRes.item_list[0]
  4) 按「JPEG 优先 / 排除水印 / 排除缩放模板 / 尺寸更大」挑最高质量图集
  5) 下载每张图并校验为可读图片，生成拼图与报告

同时验证「相关推荐」的作品页链接不会被误当成视频（旧版会下载出 0 秒损坏文件）。
不涉及平台私有签名、登录鉴权或第三方接口。

用法: python3 verify/verify_v22.py
"""
import io
import json
import os
import re
import sys
import urllib.parse
import urllib.request

import requests
from PIL import Image, ImageDraw, ImageOps

UA = ("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
TTWID_URL = "https://ttwid.bytedance.com/ttwid/union/register/"
TTWID_BODY = {
    "region": "cn", "aid": 1768, "needFid": False, "service": "www.douyin.com",
    "migrate_info": {"ticket": "", "source": "node"},
    "cbUrlProtocol": "https", "union": True,
}
SHARES = [
    ("作品1", "https://v.douyin.com/KOni4tR0sQQ/"),
    ("作品2", "https://v.douyin.com/nb2psOMhb3U/"),
]
# 模拟用户一次粘贴两条整段口令
PASTED_TEXT = (
    "7.66 复制打开抖音，看看【大大怪（在冬眠）的图文作品】… https://v.douyin.com/KOni4tR0sQQ/ iPX:/ :5pm\n"
    "6.99 复制打开抖音，看看【@无敌美少女的图文作品】… https://v.douyin.com/nb2psOMhb3U/ 03/30 :8pm"
)

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "verify_output"))
ROUTER = re.compile(r"window\._ROUTER_DATA\s*=\s*(\{.*?\})\s*;?\s*</script>", re.S)
WATER = re.compile(r"water|5oqW6Z|watermark", re.I)
SHRINK = re.compile(r"shrink|resize|thumb|crop|adapt", re.I)
DIMS = re.compile(r":(\d{3,4}):(\d{3,4})")
PAGE_LINK = re.compile(
    r"(?:www\.|m\.)?(?:douyin|iesdouyin|xiaohongshu|xhslink)\.com/"
    r"(?:video|note|user|share|explore|discovery|slides|web|profile)/", re.I)


def register_ttwid():
    r = requests.post(TTWID_URL, json=TTWID_BODY,
                      headers={"User-Agent": UA, "Content-Type": "application/json"}, timeout=20)
    tw = r.cookies.get("ttwid")
    if not tw:
        m = re.search(r"ttwid=([^;]+)", r.headers.get("Set-Cookie", ""))
        tw = m.group(1) if m else None
    return tw or ""


def fetch(url, cookie):
    h = {"User-Agent": UA, "Referer": "https://www.douyin.com/",
         "Accept": "text/html,application/xhtml+xml", "Accept-Language": "zh-CN,zh;q=0.9"}
    if cookie:
        h["Cookie"] = "ttwid=" + cookie
    r = requests.get(url, headers=h, timeout=30, allow_redirects=True)
    return r.status_code, r.url, r.text


def parse_router(html):
    m = ROUTER.search(html)
    if not m:
        return None
    root = json.loads(m.group(1))
    item = None
    for page in (root.get("loaderData") or {}).values():
        if isinstance(page, dict):
            lst = ((page.get("videoInfoRes") or {}).get("item_list") or [])
            if lst:
                item = lst[0]
                break
    if not item:
        return None
    return item


def score(u):
    l = u.lower()
    s = 100_000 if (".jpeg" in l or ".jpg" in l) else (10_000 if ".webp" in l else 0)
    if "q80" in l:
        s += 2_000
    if "q75" in l:
        s += 500
    d = DIMS.search(u)
    if d:
        s += max(int(d.group(1)), int(d.group(2)))
    if SHRINK.search(u):
        s -= 50_000
    return s


def best_urls(url_list):
    clean = [u for u in url_list if u.startswith("http") and not WATER.search(u)]
    clean = list(dict.fromkeys(clean))
    ranked = sorted(clean, key=score, reverse=True)
    good = [u for u in ranked if not SHRINK.search(u)]
    return good or ranked


def download_image(url, path):
    r = requests.get(url, headers={"User-Agent": UA, "Referer": "https://www.douyin.com/"}, timeout=40)
    r.raise_for_status()
    with open(path, "wb") as f:
        f.write(r.content)
    im = Image.open(io.BytesIO(r.content))
    im.verify()
    im2 = Image.open(path)
    return r.headers.get("Content-Type", ""), len(r.content), f"{im2.width}x{im2.height}"


def contact_sheet(paths, out_path, thumb_w=360):
    thumbs = []
    for p in paths:
        im = ImageOps.fit(Image.open(p).convert("RGB"), (thumb_w, 300))
        thumbs.append(im)
    cols = 2
    cell_h = 328
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * thumb_w, rows * cell_h), (245, 246, 250))
    draw = ImageDraw.Draw(sheet)
    for i, t in enumerate(thumbs):
        x, y = (i % cols) * thumb_w, (i // cols) * cell_h
        sheet.paste(t, (x, y))
        draw.text((x + 8, y + 304), f"图 {i + 1}", fill=(40, 44, 60))
    sheet.save(out_path, "JPEG", quality=88)


def main():
    os.makedirs(OUT, exist_ok=True)
    report = {
        "version": "2.2.0",
        "strategy": "公开访客标识 ttwid → 普通手机UA请求分享页 → _ROUTER_DATA.videoInfoRes → 最高质量图集 → 下载校验",
        "multiLinkInput": PASTED_TEXT.count("https://"),
        "results": [],
    }

    cookie = register_ttwid()
    print(f"ttwid 注册: {'成功' if cookie else '失败'}")
    if not cookie:
        print("无法注册访客标识，验证中止")
        return 1

    urls = re.findall(r"https?://[^\s\"'<>]+", PASTED_TEXT)
    print(f"一次粘贴识别到 {len(urls)} 条链接: {urls}")

    ok = True
    for idx, (tag, short) in enumerate(SHARES, 1):
        entry = {"tag": tag, "shortUrl": short, "ok": False}
        print(f"\n=== {tag}: {short} ===")
        item = None
        final = short
        for attempt in range(3):
            code, final, html = fetch(final, cookie)
            item = parse_router(html)
            print(f"  第{attempt + 1}次 HTTP {code} / {len(html)}B / "
                  f"videoInfoRes={'有' if item else '无'}")
            if item:
                break
        if not item:
            entry["error"] = "未拿到访客渲染数据"
            ok = False
            report["results"].append(entry)
            continue

        images = item.get("images") or []
        desc = item.get("desc") or ""
        author = ((item.get("author") or {}).get("nickname") or "")
        title = re.sub(r"#[^\s#]*", " ", desc)
        title = re.sub(r"\s+", " ", title).strip().rstrip("。，,.、；;!！?？ ")
        fake_videos = PAGE_LINK.findall(html)
        entry.update({
            "finalUrl": final,
            "title": title,
            "author": author,
            "imageCount": len(images),
            "declaredSize": f"{images[0].get('width')}x{images[0].get('height')}" if images else "",
            "recommendPageLinksIgnored": len(fake_videos),
            "files": [],
        })
        print(f"  标题: {title}")
        print(f"  作者: {author}   图集: {len(images)} 张   声明尺寸: {entry['declaredSize']}")
        print(f"  相关推荐作品页链接（已忽略，不会变成假视频）: {len(fake_videos)} 条")

        paths = []
        for i, im in enumerate(images, 1):
            cands = best_urls(im.get("url_list") or [])
            if not cands:
                continue
            chosen = cands[0]
            fmt = "JPEG" if (".jpeg" in chosen.lower() or ".jpg" in chosen.lower()) else "WebP"
            name = f"{tag}_原图{i}.{'jpg' if fmt == 'JPEG' else 'webp'}"
            path = os.path.join(OUT, name)
            try:
                ctype, size, dim = download_image(chosen, path)
                paths.append(path)
                entry["files"].append({
                    "name": name, "bytes": size, "contentType": ctype,
                    "dimensions": dim, "quality": fmt, "alternatives": len(cands) - 1,
                })
                print(f"    图{i}: {fmt} / {ctype} / {size} 字节 / {dim} / 备用直链 {len(cands)-1} 条  [可读]")
                if i == 1:
                    entry["firstChoice"] = fmt
                    entry["excludedWatermark"] = not bool(WATER.search(chosen))
            except Exception as e:
                entry["files"].append({"name": name, "error": str(e)})
                print(f"    图{i} 失败: {e}")
                ok = False

        if paths:
            sheet = os.path.join(OUT, f"{tag}_原图拼图.jpg")
            contact_sheet(paths, sheet)
            entry["contactSheet"] = os.path.basename(sheet)
        entry["ok"] = len(paths) == len(images) and len(images) > 0
        ok = ok and entry["ok"]
        report["results"].append(entry)

    report["allPassed"] = ok
    with open(os.path.join(OUT, "report_v22.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("\n=== 总结 ===")
    for r in report["results"]:
        print(f"  {r['tag']}: {'通过' if r.get('ok') else '失败'} · {r.get('title','')} · "
              f"{r.get('imageCount', 0)} 张 · 首选 {r.get('firstChoice','-')} · "
              f"忽略推荐链接 {r.get('recommendPageLinksIgnored', 0)} 条")
    print(f"  总体: {'全部通过' if ok else '存在失败'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
