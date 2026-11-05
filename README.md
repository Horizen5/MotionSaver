# 动态存 · MotionSaver 2.1

一个**全新自研、MIT 协议**的 Android 应用：粘贴分享链接 → 一次解析出**图文 / 视频 / 音频 / 动图**四类资源 → 按需勾选下载 → 按**作品标题**命名保存 → 实况图合成为**小米相册可识别的单文件动态照片**。

> 不含任何第三方商业应用的反编译源码，不逆向平台私有签名，不绕过鉴权，不预置第三方解析端点。详见 [DISCLAIMER.md](DISCLAIMER.md)。

**2.1 更新**：修好了抖音图文解析。旧版用普通移动端 UA 只能拿到未渲染的页面壳，结果恒为「0 个资源」；新版改为命中平台为 SEO 输出的服务端渲染页，解析其中的 `schema.org` JSON-LD 结构化数据，**真实两条图文链接已验证分别解析出 4 张 / 3 张图并全部下载成功**。原理与验证见第三节。

---

## 一、功能矩阵

| 需求 | 实现 |
| --- | --- |
| **图文解析** | 图集批量下载（`标题_01.webp`…），正文另存 `.txt`，作者/标题/发布时间一并提取；抖音图文经 JSON-LD 图集直连 |
| **视频解析** | 视频直链下载，按作品标题命名，写入 `Movies/MotionSaver` |
| **音频解析** | 平台给音频直链则直接下载；没有时可用 `MediaExtractor` 从视频**流拷贝抽 AAC 音轨**（不重编码），输出 `.m4a` |
| **动图解析** | GIF / 动图 WebP 直接保存；实况图（Live Photo）合成小米动态照片 |
| **小米可看的动态图，不是视频** | 输出**单个 `.jpg`**：JPEG 封面 + 尾部追加 MP4 + XMP 偏移元数据。相册显示为带动态角标的图片，长按播放 |
| **无损** | JPEG / MP4 / AAC 原始字节直接拼接或拷贝，**不重编码**，因此不需要 FFmpegKit（省 40MB AAR） |
| **文件名就是视频的名字** | 取作品标题 → 清 emoji/非法字符 → 折叠空白 → 80 字 code point 安全截断 → 重名自动加 `_2` |
| **平台** | 抖音、小红书、**任意网页**（公开页兜底）、**自填解析端点**；知乎未接入 |
| **性能** | `arm64-v8a` 单架构 + release R8 + 64KB 缓冲流式落盘 + Range 断点续传 + 指数退避重试 |
| **抗页面变化** | 常规 UA 跟随短链 → 爬虫 UA 触发 SSR → 概率性空壳最多重试 5 次 → 三层资源发现兜底 |
| **交互** | 四 Tab：解析下载（可勾选）/ 下载队列（实时进度）/ 记录（持久化）/ 设置（端点、开关、自检） |

---

## 二、快速开始

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk

cd MotionSaver
./gradlew :core:test                 # 61 个纯 JVM 单元测试
./gradlew :app:assembleDebug         # 产出可安装 APK
./gradlew :app:installDebug          # 装到真机（需 adb 已连接）
python3 verify/verify_live.py        # 真实网络端到端：解析两条抖音图文并下载校验
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（`samples/MotionSaver-2.1.0-debug.apk` 是同一份的交付副本）。

**在手机上**：把链接或整段口令粘贴进输入框 → 点「① 解析」→ 结果按四类分组列出、默认全选 → 取消不想下的 → 点「② 下载选中」。也可从抖音/小红书「分享 → 复制链接」直接把文本送进 App。

---

## 三、解析原理：为什么现在能真的出图

**旧版为什么是 0 个资源。** 用普通移动端 UA 请求 `https://v.douyin.com/xxx/` 会 302 到
`https://www.iesdouyin.com/share/note/{id}/`，返回约 37KB 的**纯前端壳**：`window._ROUTER_DATA`
里只有 `itemId`、`query`、UA 等路由上下文，**没有任何图集地址**。页面里的图片 CDN 链接只有
favicon 和 logo（会被黑名单过滤）。所以再怎么改正则也扫不出图——不是解析器写得不够复杂，
而是请求方式决定了根本没拿到数据。

**关键发现。** 平台为了被搜索引擎收录，对**搜索引擎爬虫 UA** 会走服务端渲染（SSR），
直接把作品数据以 `schema.org` JSON-LD 写进 HTML：

```html
<script type="application/ld+json">
{"@type":"article","headline":"人的本质是赌徒不赌就是在赌自己输#fx战士久留美#股票行情#",
 "articleBody":"人的本质是赌徒，不赌！就是在赌自己输！…",
 "image":["https://p3-pc-sign.douyinpic.com/…webp?…","https://p9-pc-sign.douyinpic.com/…webp?…", …],
 "author":{"@type":"Person","name":"大大怪（在冬眠）"},"datePublished":"2026-10-18 12:38"}
</script>
```

`image` 数组就是**完整图集直链**，`headline` 是干净标题，`articleBody` 是正文。

**APP 里的实现**（`PublicPageParser.fetchForParsing` + `JsonLdParser`）：

1. 第一次用常规 UA 请求短链，跟随 302 拿到带分享参数的完整地址；
2. 后续用爬虫 UA 直接请求该完整地址，命中 SSR 页面；
3. 平台 SSR 是**概率性返回**（实测同一链接前 1~2 次可能仍给空壳），最多重试 5 次，命中即早停；
4. `JsonLdParser` 提取 `image` 数组（只取数组形式，天然避开 `author.image` 头像）、
   `thumbnailUrl`、`contentUrl` / `embedUrl` 中的视频音频，并忽略 `BreadcrumbList` 导航块；
5. 标题/作者/正文优先用 JSON-LD，再落到 `og:*` 与口令兜底；
6. 图集按数组原顺序编号，与页面正则发现的资源按 URL 去重合并，再统一重新编号。

**合规边界**：只读公开页面自身声明的结构化数据。不构造 `a_bogus` / `x-s` 等私有签名，
不调用需要登录鉴权的内部接口，不绕过登录墙，浏览器直接可见同样内容。

**真实验证**（`verify/verify_live.py`，与 APP 完全相同的策略）：

| 链接 | 第 1 次（常规 UA） | 第 2 次（爬虫 UA） | 标题 | 作者 | 图集 | 下载校验 |
| --- | --- | --- | --- | --- | --- | --- |
| `v.douyin.com/KOni4tR0sQQ/` | 37KB / 0 张 | 68KB / **4 张** | 人的本质是赌徒，不赌！就是在赌自己输！ | 大大怪（在冬眠） | 4 张 webp | 4/4 HTTP 200、PIL 可读 |
| `v.douyin.com/nb2psOMhb3U/` | 37KB / 0 张 | 64KB / **3 张** | 我想说 天确实冷了 | @无敌美少女 | 3 张 webp | 3/3 HTTP 200、PIL 可读 |

图片尺寸 1080×778 ~ 1296×1216，全部落盘到 `verify_output/`（该目录不入库）。
另有两个页面被抽成 fixture 放进 `core/src/test/resources/`，由 `DouyinRealPageTest` 做离线回归。

---

## 四、让视频 / 音频解析跑全：自填解析端点

**图文路径已可直接用**（第三节）。但抖音 / 小红书的公开页通常**不声明视频或音频直链**，
这类资源需要平台登录态或私有接口才能拿到。纯公开页路径下，App 会明确提示而不会伪造数据。

要拿到完整四类资源，在「设置 → 解析端点」填一个**你有合法授权**的数据源（自建服务 / yt-dlp 自托管 / 已授权 API）。App 不预置任何第三方私有接口。

示例：端点 URL 模板

```
https://your.host/parse?url={url}
```

返回 JSON（字段名可在设置里改）：

```json
{
  "title": "作品标题",
  "author": "作者",
  "caption": "图文正文",
  "id": "abc123",
  "images":   ["https://cdn/1.jpg", "https://cdn/2.png"],
  "videos":   ["https://cdn/v.mp4"],
  "audios":   ["https://cdn/bgm.m4a"],
  "animated": ["https://cdn/a.gif"],
  "cover":      "https://cdn/live_cover.jpg",
  "live_video": "https://cdn/live.mp4"
}
```

- `{url}` 会被 URL 编码后的分享链接替换；`{raw}` 是未编码版本。
- 请求头支持多行 `Key: Value`（例如 `Authorization: Bearer xxx`）。
- 若端点返回的是扁平 URL 列表，App 会按 URL 特征自动归类到四类。
- 启用端点后，它优先于平台解析器；关闭即回落到公开页解析。

改完端点**不需要重编译**，保存后下次解析即生效。

---

## 五、工程结构

```
MotionSaver/
├── core/                              纯 JVM 模块（无 Android 依赖，可独立测试）
│   └── com/motionsaver/core/
│       ├── MediaModels.kt             平台 / 资源种类 / 四类媒体 / 解析结果模型
│       ├── ParserContract.kt          HttpFetcher / MediaParser 接口、分享口令清洗
│       ├── PublicPageParser.kt        公开页解析基类：多 UA 重试 + JSON-LD + 三层发现 + 实况检测
│       ├── JsonLdMedia.kt             ★ schema.org JSON-LD 结构化数据解析（图集/标题/作者/音视频）
│       ├── PlatformParsers.kt        抖音 / 小红书 / 通用网页 + 可插拔注册表
│       ├── EndpointConfig.kt         自填端点配置（URL 模板 / Header / 字段映射）
│       ├── EndpointParser.kt         端点 JSON → 四类资源映射
│       ├── MiniJson.kt               零依赖极简 JSON 读取（core 不引第三方库）
│       ├── ShareLinkNormalizer.kt    短链归一 / 平台识别 / 作品 ID 提取
│       ├── MediaTypeDetector.kt      按 URL / mime 判定四类资源
│       ├── TitleSanitizer.kt         文件名清洗
│       ├── FileNameAllocator.kt      标题命名 + 图集序号 + 重名去重
│       ├── MotionPhotoXmp.kt         Motion Photo XMP 元数据构造
│       ├── MotionPhotoWriter.kt      JPEG + MP4 → 单文件动态照片（无损）
│       └── MotionPhotoVerifier.kt    独立格式校验器
│   └── src/test/resources/            真实抓取的抖音图文页 fixture（仅保留 JSON-LD 块）
├── app/                               Android Compose 应用
│   └── com/motionsaver/app/
│       ├── MotionSaverApp.kt          轻量依赖容器
│       ├── MainActivity.kt            单 Activity + Share Intent
│       ├── MainViewModel.kt           解析/队列/记录/设置 状态机
│       ├── data/
│       │   ├── OkHttpFetcher.kt       core 网络接口实现
│       │   ├── Downloader.kt          流式下载 + Range 续传 + 退避重试
│       │   ├── MediaStoreSaver.kt     四类内容各写入对应媒体库目录
│       │   ├── AudioExtractor.kt      视频无损抽音轨（MediaExtractor 流拷贝）
│       │   ├── SettingsStore.kt       设置持久化
│       │   ├── HistoryStore.kt        下载记录持久化
│       │   └── SaveMediaUseCase.kt    解析→勾选→批量下载→合成→命名→落盘
│       └── ui/AppUi.kt                四 Tab Compose 界面 + 动态照片自检页
├── verify/verify_live.py              ★ 真实网络端到端验证（含图片下载与可读性校验）
├── samples/
│   ├── cover.jpg / clip.mp4           真实 JPEG 封面与 H.264/AAC 片段
│   └── motion_photo_sample.jpg        ★ 合成好的动态照片样例，拷到小米手机即可验证
├── app/src/main/assets/selfcheck/     App 内置自检素材
└── （APK 二进制不入库，从仓库 Releases 下载可安装包）
```

---

## 六、动态照片格式说明

依据[小米澎湃 OS 动态照片文档](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2000)与公开 Motion Photo 规范：

```
┌─────────────────────────────────────┐
│ JPEG (FFD8 … FFD9)                  │
│   └─ APP1 段：XMP 包                 │
│       ├─ GCamera:MicroVideoVersion=1 │
│       ├─ GCamera:MicroVideoOffset=N │  ← 尾部视频字节数
│       ├─ GCamera:MotionPhoto=1       │
│       └─ Container:Directory         │
│          └─ Item:Length=N / video/mp4│
├─────────────────────────────────────┤
│ MP4 原始字节（长度 = N）             │  ← 相册据此切出并播放
└─────────────────────────────────────┘
```

- 同时写经典 `MicroVideo*` 与新版 `MotionPhoto*` / `Container:Directory` 两套字段，覆盖不同相册实现。
- XMP 段带 4KB padding，方便相册原地改写元数据而不移动视频数据。
- 重复合成会**替换**已有 XMP 段，不叠加。
- 未使用小米官方 SDK（需申请账号、限 HyperOS + 大陆机型），按标准自行合成。

**真机验证**：把 `samples/motion_photo_sample.jpg` 拷到小米相册，应显示为带动态角标的图片、按住可播放 2 秒带声片段。仓库已用独立 Python 解析器校验：`MicroVideoOffset` 与尾部 MP4 长度精确一致、ftyp 正确、JPEG 段 EOI 完整、尾部字节与源 MP4 **逐字节相同**。App 内「设置 → 运行自检」可在真机上复验同样结论。

---

## 七、相比现有开源方案的改进点

调研了 [QingZai 轻载](https://github.com/Yukioooooo0/QingZai)（MIT）、[YunX 云析](https://mygit.top/repository/1325498898)（AGPL-3.0）、[DouyinDL](https://github.com/noctiro/DouyinDL)（AGPL-3.0）、抖净等公开项目，主要补齐：

| # | 现有短板 | 本项目的做法 |
| --- | --- | --- |
| 1 | 「动图」多是 mp4/GIF，相册仍当视频 | 按小米 Motion Photo 标准合成**单文件动态照片** |
| 2 | 文件名多为 ID / 时间戳 | 用**作品标题**命名，含完整清洗与重名策略 |
| 3 | 标题含 emoji / 非法字符会写入失败 | 白名单清洗 + 汉字间空格消除 + code point 安全截断 |
| 4 | 解析器硬编码，接口一变全废 | **可插拔注册表** + JSON-LD / 三层资源发现 + **自填端点** |
| 5 | 只做视频，缺图文/音频/动图统一处理 | **四类资源一次解析、分组展示、按需勾选** |
| 6 | 音频只能另找工具 | 平台直链优先，缺失时**视频流拷贝抽音轨**，不重编码 |
| 7 | 合轨 FFmpeg 重编码掉画质 | 容器级**无损拼接**，去掉大体积 AAR |
| 8 | 视频整包载入内存 | 64KB 缓冲流式落盘 + Range 断点续传 + 重试 |
| 9 | 成品写私有目录，相册扫不到 | 四类内容分别写 **MediaStore** 的 Pictures / Movies / Music / Downloads |
| 10 | 缺格式自检 | core 内置 `MotionPhotoVerifier` + App 真机自检页 |
| 11 | 无队列/历史/设置 | 四 Tab：实时队列、持久化记录、设置与端点配置 |
| 12 | 图文页用普通 UA 拿不到图集，恒显示 0 个资源 | **爬虫 UA 触发 SSR + 概率性重试 + JSON-LD 图集解析**，真实链接已验证 |

---

## 八、合规边界

- **只请求用户粘贴的公开分享链接**，读取页面自身公开的 `og:*` / JSON-LD 结构化数据 / 内嵌 JSON；不生成 `a_bogus`、`x-s` 等私有签名，不请求需鉴权的内部接口，不绕过登录墙。
- 搜索引擎爬虫 UA 只用于触发平台公开的 SEO 服务端渲染输出，读取的是任何爬虫都能拿到的页面内容，不属于绕过鉴权。
- 公开页若未暴露直链，App 会返回明确警告，**不伪造数据**。需要完整视频/音频能力请接入自己具备合法授权的数据源（第四节），下载链路不用改。
- 平台页面结构与 SSR 策略会变，公开页解析可能失效；这是该方案的固有取舍。
- 仅供保存自己有权保存的内容，遵守平台条款与适用法律。

---

## 九、取舍说明

为保证「一次编译通过、可安装」，技术栈做了收敛，架构分层与接口保持一致，后续可无缝替换：

| 原计划 | 实际 | 原因 |
| --- | --- | --- |
| Hilt | 轻量 `MotionSaverApp.Container` | 避免 KSP/kapt 版本耦合 |
| Room + WorkManager | SharedPreferences + StateFlow | 依赖少、包体小，接口已隔离 |
| Media3 预览 | 未内置 | 与核心链路无关 |
| FFmpegKit | 未引入 | 无损拼接 / 抽音轨都不需要编解码器，省 40MB |

---

## 十、测试

```bash
./gradlew :core:test
python3 verify/verify_live.py
```

**61 个 JVM 单元测试，全部通过**（其中 7 个是 v2.1 新增的「真实抖音图文页」回归，
使用从用户提供的两条分享链接抓取的 JSON-LD 页面做 fixture），覆盖：四类资源解析与归类、
图集序号命名、音频/动图识别、自填端点映射与优先级、短链归一、极简 JSON 与转义还原、
媒体类型判定、XMP 合成与独立校验、无损拼接、重复合成、非法输入拒绝、JPEG 合法性、
标题清洗、重名分配、公开页解析、实况图识别、无直链告警、注册表可插拔、
**JSON-LD 图集解析 / 导航块与头像过滤 / SSR 空壳重试告警**。

---

## License

[MIT](LICENSE) · 详见 [DISCLAIMER.md](DISCLAIMER.md)
