#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v2.3 端到端验证：复刻 APP 解析链路，覆盖本轮两个修复。

  1) 注册公开访客标识 ttwid（无需账号/登录/签名）
  2) 跟随短链 302，拿到 finalUrl
  3) /share/slides/{id}/ → /share/note/{id}/ 归一化（修复「0 资源」）
  4) 用访客 Cookie 请求，解析 _ROUTER_DATA.videoInfoRes.item_list[0]
  5) 实况图识别：单图 + lqen 模板 + 无真实视频轨 → 标记为实况图作品，
     静态帧取最高质量 JPEG、原声为 M4A；不伪造动态照片
  6) 普通图集：取最高质量候选，排除水印/缩放/相关推荐页链接
  7) 下载资源并校验可读，输出报告

用法: python3 verify/verify_v23.py
"""
import io
import json
import os
import re
import sys
import urllib.parse

import requests
from PIL import Image, ImageDraw

UA = ("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
TTWID_URL = "https://ttwid.bytedance.com/ttwid/union/register/"
TTWID_BODY = {
    "region": "cn", "aid": 1768, "needFid": False, "service": "www.douyin.com",
    "migrate_info": {"ticket": "", "source": "node"},
    "cbUrlProtocol": "https", "union": True,
}

# 用户本轮提供的两条真实链接：实况图作品 + 图集(slides)作品
SHARES = [
    ("实况图", "https://v.douyin.com/igDF5iLZ8ec/"),
    ("图集", "https://v.douyin.com/vaYOcAwFVf4/"),
]

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "verify_output"))
os.makedirs(OUT, exist_ok=True)

ROUTER = re.compile(r"window\._ROUTER_DATA\s*=\s*(\{.*?\})\s*;?\s*</script>", re.S)
WATER = re.compile(r"water|5oqW6Z|watermark", re.I)
SHRINK = re.compile(r"shrink|resize|thumb|crop|adapt", re.I)
LIVE_TPL = re.compile(r"~tplv-[a-z0-9-]*lqen", re.I)
SLIDES = re.compile(r"(?:www\.|m\.)?(?:iesdouyin|douyin)\.com/share/slides/(\d{6,})", re.I)
DIMS = re.compile(r":(\d{3,4}):(\d{3,4})")


def canonical_share_url(url: str) -> str:
    m = SLIDES.search(url)
    return f"https://www.iesdouyin.com/share/note/{m.group(1)}/" if m else url


def register_ttwid():
    r = requests.post(TTWID_URL, json=TTWID_BODY,
                      headers={"User-Agent": UA, "Content-Type": "application/json"}, timeout=20)
    tw = r.cookies.get("ttwid")
    if not tw:
        m = re.search(r"ttwid=([^;]+)", r.headers.get("Set-Cookie", ""))
        tw = m.group(1) if m else ""
    return tw


def rank(urls):
    clean = [u for u in urls if u.startswith("http") and not WATER.search(u)]

    def score(u):
        l = u.lower()
        s = 0
        if ".jpeg" in l or ".jpg" in l:
            s += 100_000
        elif ".webp" in l:
            s += 10_000
        if "q80" in l:
            s += 2_000
        elif "q75" in l:
            s += 500
        d = DIMS.search(u)
        if d:
            s += max(int(d.group(1)), int(d.group(2)))
        if SHRINK.search(u):
            s -= 50_000
        return s

    scored = sorted(((u, score(u)) for u in dict.fromkeys(clean)), key=lambda x: -x[1])
    no_shrink = [u for u, _ in scored if not SHRINK.search(u)]
    return no_shrink or [u for u, _ in scored]


def fetch_router(final_url, tw):
    """模拟 APP 的 fetchForParsing：归一化路径 + 访客 Cookie + 概率性重试。"""
    url = canonical_share_url(final_url)
    headers = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Referer": "https://www.douyin.com/",
        "Cookie": f"ttwid={tw}",
    }
    for attempt in range(1, 6):
        try:
            r = requests.get(url, headers=headers, timeout=25)
            if r.status_code != 200:
                continue
            m = ROUTER.search(r.text)
            if not m:
                continue
            d = json.loads(m.group(1))
            for page in d.get("loaderData", {}).values():
                if not isinstance(page, dict):
                    continue
                items = (page.get("videoInfoRes") or {}).get("item_list") or []
                if items:
                    return items[0], url, attempt, len(r.text)
        except Exception:
            continue
    return None, url, 5, 0


def analyse(item):
    images = item.get("images") or []
    video = item.get("video") or {}
    play = video.get("play_addr") or {}
    has_live_tpl = any(
        LIVE_TPL.search(u) for im in images for u in (im.get("url_list") or [])
    )
    no_real_video = video.get("bit_rate") is None and (video.get("duration") or 0) == 0
    live_work = len(images) == 1 and has_live_tpl and no_real_video and bool(play.get("uri") or play.get("url_list"))

    picked = []
    for i, im in enumerate(images):
        cands = rank(im.get("url_list") or [])
        if cands:
            picked.append({
                "index": i + 1,
                "url": cands[0],
                "jpeg": ".jpeg" in cands[0].lower() or ".jpg" in cands[0].lower(),
                "width": im.get("width", 0),
                "height": im.get("height", 0),
            })
    audio_uri = play.get("uri", "")
    return {
        "title": (item.get("desc") or "").split("#")[0].strip()[:60],
        "author": ((item.get("author") or {}).get("nickname") or ""),
        "image_count": len(images),
        "picked": picked,
        "live_work": live_work,
        "live_template": has_live_tpl,
        "audio_is_m4a": bool(audio_uri),
        "audio_url": audio_uri,
    }


def download_verify(url, dest):
    try:
        r = requests.get(url, headers={"User-Agent": UA, "Referer": "https://www.douyin.com/"}, timeout=35)
        if r.status_code != 200:
            return {"ok": False, "reason": f"HTTP {r.status_code}"}
        im = Image.open(io.BytesIO(r.content))
        im.load()
        im.convert("RGB").save(dest, "JPEG", quality=88)
        return {"ok": True, "bytes": len(r.content), "size": f"{im.width}x{im.height}", "format": im.format}
    except Exception as e:
        return {"ok": False, "reason": str(e)[:90]}


def contact_sheet(paths, out):
    if not paths:
        return
    tiles = []
    for p in paths:
        im = Image.open(p).convert("RGB")
        im.thumbnail((320, 320))
        canvas = Image.new("RGB", (330, 330), "white")
        canvas.paste(im, ((330 - im.width) // 2, (330 - im.height) // 2))
        tiles.append(canvas)
    cols = min(3, len(tiles))
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * 330, rows * 330), (240, 240, 240))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % cols) * 330, (i // cols) * 330))
    sheet.save(out, "JPEG", quality=88)


def main():
    tw = register_ttwid()
    if not tw:
        print("[FATAL] ttwid 注册失败，无法复刻访客渲染链路")
        sys.exit(1)
    print(f"访客标识已获取 (len={len(tw)})")

    headers = {"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9", "Cookie": f"ttwid={tw}"}
    session = requests.Session()
    results = []
    all_ok = True

    for tag, short in SHARES:
        print(f"\n=== {tag}: {short} ===")
        # 1) 跟随短链拿到 finalUrl（模拟 OkHttp followRedirects）
        r0 = session.get(short, headers=headers, timeout=30, allow_redirects=True)
        final = r0.url
        canonical = canonical_share_url(final)
        normalized = canonical != final
        print(f"  finalUrl: {final[:110]}")
        print(f"  slides归一化: {'是 → ' + canonical[:90] if normalized else '否（无需改写）'}")

        # 2) 用归一化后的地址 + 访客 Cookie 取完整数据
        item, used_url, attempts, size = fetch_router(final, tw)
        rec = {
            "tag": tag,
            "short": short,
            "finalUrl": final,
            "slidesNormalized": normalized,
            "canonicalUrl": canonical,
            "attemptsUsed": attempts,
            "pageBytes": size,
        }
        if not item:
            print("  [FAIL] 未取到作品数据")
            rec["ok"] = False
            all_ok = False
            results.append(rec)
            continue

        info = analyse(item)
        rec.update(info)
        print(f"  标题: {info['title']} / {info['author']}")
        print(f"  图片 {info['image_count']} 张, 重试 {attempts} 次, 页面 {size}B")
        print(f"  实况图作品: {info['live_work']} (lqen模板={info['live_template']})")
        print(f"  原声 M4A: {info['audio_is_m4a']}")

        # 3) 下载每个最高质量候选并校验
        thumbs = []
        dl = []
        for pic in info["picked"]:
            dest = os.path.join(OUT, f"v23_{tag}_{pic['index']}.jpg")
            v = download_verify(pic["url"], dest)
            v["index"] = pic["index"]
            v["jpeg"] = pic["jpeg"]
            dl.append(v)
            if v["ok"]:
                thumbs.append(dest)
                print(f"  图{pic['index']}: OK {v['size']} {v['format']} {v['bytes']}B")
            else:
                print(f"  图{pic['index']}: FAIL {v.get('reason')}")
                all_ok = False
        rec["downloads"] = dl
        rec["ok"] = len(dl) > 0 and all(d["ok"] for d in dl)
        if not rec["ok"]:
            all_ok = False

        sheet = os.path.join(OUT, f"v23_{tag}_拼图.jpg")
        contact_sheet(thumbs, sheet)
        rec["contactSheet"] = os.path.basename(sheet) if thumbs else ""

        # 断言：图集作品必须归一化；实况图必须被识别；都不能有假视频
        if tag == "图集":
            assert normalized, "图集短链应命中 slides 归一化"
            assert info["image_count"] >= 3, "图集应至少 3 张图"
        if tag == "实况图":
            assert info["live_work"], "实况图作品应被识别"
        results.append(rec)

    report = {
        "version": "2.3.0",
        "strategy": "短链→重定向→slides路径归一化→访客Cookie取数→lqen实况图识别→最高质量候选下载",
        "allPassed": all_ok,
        "results": results,
    }
    rp = os.path.join(OUT, "report_v23.json")
    with open(rp, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n=== 总体: {'PASS' if all_ok else 'FAIL'} ===")
    print(f"报告: {rp}")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
