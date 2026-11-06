# 动态照片（Motion Photo）实现与播放说明

> 依据你上传的 `MVIMG_20261106_230518.zip` 实测解析，并对照 Android 官方规范与公开技术资料整理。
> 样本为无损压缩（zip/deflate），内部文件字节完整，SHA-256 `255b4a44bee49ae1c45071d3ab32503c185ba4ac5a5db004a671e63678e471d0`。

---

## 一、你这份样本实测长什么样

压缩包里只有一个文件 `MVIMG_20261106_230518.jpg`，**5,983,189 字节**。它不是「一种新图片格式」，而是**一个 JPEG 文件后面直接拼了一段 MP4**：

| 区段 | 偏移 / 长度 | 内容 |
| --- | --- | --- |
| JPEG SOI + Exif APP1 | `0` / 25,079 B | 相机参数、缩略图 |
| **XMP APP1** | `25,081` / 615 B | 关键元数据，见下 |
| DQT / SOF0 / DHT / SOS | `25,698` 起 | 静态封面 JPEG 压缩数据 |
| JPEG EOI | `2,993,061` | 封面结束（`FF D9`） |
| **内嵌 MP4** | `2,993,063` / 2,990,126 B | `ftyp mp42`，视频 + 音频 |

封面是 **3060×4080 JPEG**；尾部视频是 **H.264 1080×1440、30fps、45 帧、1.968 秒**，另有 **AAC 44.1kHz 单声道** 音轨；`moov/mvhd` 时长 1.968s、`free` 1,039 B、`mdat` 2,986,660 B。用 `ffmpeg` 从抽出的 MP4 里取第 1 帧成功（1080×1440），说明视频段本身是完整可解码的独立 MP4。

XMP 原文（小米 **MicroVideo V1** 老规范）：

```xml
<rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:MiCamera="http://ns.xiaomi.com/photos/1.0/camera/"
  GCamera:MicroVideoVersion="1"
  GCamera:MicroVideo="1"
  GCamera:MicroVideoOffset="2990126"
  GCamera:MicroVideoPresentationTimestampUs="910229"
  MiCamera:XMPMeta="&lt;?xml version='1.0' ... ?&gt;"/>
```

**`MicroVideoOffset=2990126` 的语义是「从文件末尾往回数的字节数」**，也就是尾部 MP4 的长度。验算：`5,983,189 − 2,990,126 = 2,993,063`，正好等于实测的 `ftyp` 起点，完全吻合。`MicroVideoPresentationTimestampUs=910229`（约 0.91 秒）表示哪一帧对应按下快门时的静态封面。

> 结论：**你发过来的是无损的**。zip 只是 deflate 打包，JPEG 与 MP4 的原始字节一个都没动，动态片段和声音都还在。

---

## 二、实现原理：JPEG + XMP + 尾部 MP4

Android 阵营的「动态照片 / 实况照片」不是独立格式，而是一种**单文件容器策略**：

1. 文件开头是一张完整、合法的高分辨率静态图（JPEG / HEIC / AVIF），所以**任何看图软件都能打开它**，只是看到静态封面；
2. 在 JPEG 的 `APP1` 段写入 **XMP 元数据**，记录「我后面藏了视频、视频有多长、起始位置在哪」；
3. 把一段短视频 MP4（H.264/HEVC，可带 AAC 音轨）**原样追加到文件末尾**，不重新编码；
4. 相册读到 XMP，就按长度从文件尾切出 MP4 播放。

存在三代写法，彼此并不统一：

| 规范 | 关键字段 | 典型设备 |
| --- | --- | --- |
| MicroVideo V1（旧） | `GCamera:MicroVideo="1"`、`MicroVideoOffset`（距文件末尾字节数） | 小米、早期 Google Pixel、三星 |
| Android **Motion Photo 1.0**（2024-08 官方定型） | `Camera:MotionPhoto="1"`、`Container:Directory` → `Item:Semantic="MotionPhoto"` + `Item:Length` | 新 Pixel / 新系统 |
| 厂商私有 | 小米 `MiCamera:*`、OPPO O Live Photo（含 HDR GainMap）、vivo 用「图 + 视频双文件」仿 iPhone | 各厂商 |

官方 1.0 明确规定 `MicroVideo*` 系列字段**已废弃、读者应忽略**，改用 `Item:Length` 定位；但小米相册实际仍靠 `MicroVideo="1"` + `MVIMG_` 文件名前缀识别。所以最稳妥的做法是**两套都写**。命名也有讲究：官方 1.0 建议扩展名前以 `MP` 结尾（`IMG_1234MP.jpg`），老相册认 `MVIMG_` 前缀；官方正则为 `^([^\s/\\][^/\\]*MP)\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)`。

参考：[Android Developers · Motion Photo format 1.0](https://developer.android.com/media/platform/motion-photo-format)、[Android 动态图片技术深度解析（小米/Google/OPPO 三种 XMP 实例）](http://mp.weixin.qq.com/s?__biz=MzU3NTY3MTQzMg==&mid=2247565039&idx=1&sn=d0da8c638762ac33ddf43a4ece7c6ede)、[live-photo-conv 格式拆解与厂商碎片化](https://deepwiki.com/wszqkzqk/live-photo-conv/1.1-system-architecture)、[一文搞懂手机实况照片](https://bapuji.blog.csdn.net/article/details/167373731)。

---

## 三、用什么能播放、手机上怎么播

| 场景 | 能不能动 | 说明 |
| --- | --- | --- |
| **小米 / 华为 / OPPO 自带相册**、**Google 相册** | ✅ | 读 XMP 定位尾部 MP4，长按或点「动态照片 / 实况」即播放，通常带原声 |
| 系统「文件管理」直接点开 .jpg、普通看图 App、电脑看图软件 | ❌ 只显示静态封面 | 只解析 JPEG 头部，忽略尾部数据 |
| **微信 / 部分社交 App 转发** | ❌ 常见变静态 | 会**剥掉 XMP 元数据**（视频字节有时还在），相册找不到视频位置；这就是「发过去就不动了」的根因 |
| Google Photos 网页版、支持 Motion Photo 的在线查看器 | ✅ | 上传原文件（不要经过压缩/转码） |
| 命令行：`ffmpeg -i x.jpg`（部分版本）、`exiftool -MicroVideoOffset` + `dd` 切出 MP4 | ✅ | 切出来的是**原始 MP4 字节**，无损 |
| `live-photo-conv`（GStreamer/FFmpeg） | ✅ | 可创建 / 提取 / 修复被剥掉 XMP 的动态照片 |

**Android App 内播放的正确姿势**（也是这次给 MotionSaver 用的方案）：

```kotlin
val offset = fileSize - microVideoOffset   // MP4 起点
val length = microVideoOffset              // MP4 长度
mediaPlayer.setDataSource(fileDescriptor, offset, length)  // 直接从原文件偏移处播
mediaPlayer.isLooping = false
mediaPlayer.prepareAsync()
```

`MediaPlayer.setDataSource(FileDescriptor, offset, length)` 与 `MediaExtractor.setDataSource(fd, offset, length)` 都支持**带偏移的数据源**，所以不需要把 MP4 切出来另存一份，也就不会有任何重编码或画质损失。ExoPlayer 需要自定义 `DataSource` 把 `DataSpec` 的偏移透传下去；系统 `ExifInterface` 读不到 `GCamera:*` 这种自定义 XMP 命名空间，需要自己解析 APP1 或用 Adobe XMP SDK / metadata-extractor。

参考：[Working with Motion Photos（MediaPlayer / ExoPlayer 偏移播放与 XMP 写入）](https://medium.com/android-news/working-with-motion-photos-da0aa49b50c)、[MediaExtractor.setDataSource(fd, offset, length) 官方 API](https://developer.android.com/reference/kotlin/android/media/MediaExtractor)、[StackOverflow：How to play android motion photo with MediaPlayer](https://stackoverflow.com/questions/66733658/how-to-play-android-motion-live-photo-with-mediaplayer)、[Android 动态照片底层解构：无损视频流提取](https://tsight.io/articles/19463593?lang=zh)。

**手机上最省事的三条路**：① 直接用手机自带相册打开原文件；② 在 MotionSaver 的「记录」页点「预览 ›」，App 内无损播放；③ 别用微信「发送图片」传，改用文件/原图传输或数据线，否则 XMP 会被剥掉。

---

## 四、这次给 MotionSaver 加了什么（v2.5.0）

| 改动 | 说明 |
| --- | --- |
| **动图预览点开就动** | 解析结果里点「动图」卡片 → 直接按静态帧轮播（约 2~4 秒一轮），**播完一轮自动暂停、停在最后一帧，不循环**；可点画面或「↻ 再播一次」重看 |
| **App 内播放本地动态照片** | 「记录」页里 `MVIMG_*` / 含「动态照片」的文件，点预览不再甩给系统相册，而是读 XMP → `MediaPlayer.setDataSource(fd, offset, length)` 无损播放内嵌视频，同样**只播一次、播完暂停** |
| **XMP 兼容真机** | 补上 `GCamera:MicroVideo="1"`（你这份样本有、我们旧版缺），小米相册才肯把文件当动态照片；同时保留 1.0 的 `Container:Item:Length` |
| **命名兼容两代相册** | 动态照片产物改名为 `MVIMG_标题-动态照片MP.jpg`，既有老相册要的 `MVIMG_` 前缀，又满足 1.0 的 `MP.jpg` 结尾 |
| **XMP 被剥也能救** | 新增 `MotionPhotoReader`：优先读 `MicroVideoOffset` / `Item:Length`，元数据缺失时回退扫描 `ftyp` box 定位视频 |
| **无损** | 全程只做字节定位与拼接，封面 JPEG 与视频 MP4 都不重新编码 |

合规边界不变：不逆向平台私有签名、不调带鉴权接口、不把纯音频 M4A 伪装成视频。
