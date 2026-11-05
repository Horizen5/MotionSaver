# 动态存 · MotionSaver 2.2

一个**全新自研、MIT 协议**的 Android 应用：粘贴分享链接（可一次多条）→ 解析出**图文 / 视频 / 音频 / 动图** → **点开直接预览** → 圆圈选中 → 下载 → 按**作品标题**命名 → 实况图合成为**小米相册可识别的单文件动态照片**。

> 不含任何第三方商业应用的反编译源码，不逆向平台私有签名，不绕过鉴权，不预置第三方解析端点。详见 [DISCLAIMER.md](DISCLAIMER.md)。

**2.2 更新（本次）**

| 改动 | 说明 |
| --- | --- |
| **预览** | 点缩略图直接看大图；视频/音频点开交给系统播放器；下载记录点开预览已保存文件 |
| **选中态** | 缩略图右上角圆形按钮，点一下显示对勾、再点变空心圆；选中卡片浮起放大 |
| **全选方框** | 每张结果卡右上角一个复选框，点一下全选、再点取消全选 |
| **多条链接** | 一次粘贴 2~3 条整段口令，全部识别、逐个解析、各按自己标题命名 |
| **清空** | 输入框「清空」一键重置、结果卡「清空」、记录页右上角「清空下载记录」 |
| **高级设置** | 主设置页极简，端点/字段映射/并发/自检全部收进二级页 |
| **提取音频** | 解析结果页直接切换「提取音频（视频无损抽音轨）」 |
| **最高质量原图** | 图集自动选平台给出的 **JPEG 最高档**候选，排除水印与缩放模板 |
| **修掉假视频** | 不再把「相关推荐」的作品页链接当成视频，杜绝 0 秒 / 文件损坏 |
| **画质数据** | 同一条图文链接：旧版 q75.webp 34KB → 新版 JPEG 83KB~216KB |

---

## 一、功能矩阵

| 需求 | 实现 |
| --- | --- |
| **图文解析** | 图集批量下载（`标题_01.jpg`…），正文另存 `.txt`；抖音图文走访客渲染数据的 `images[].url_list`，选最高质量 JPEG |
| **视频解析** | 视频直链下载，按作品标题命名，写入 `Movies/MotionSaver` |
| **音频解析** | 平台给音频直链则直接下载；可选「提取音频」用 `MediaExtractor` 从视频**流拷贝抽 AAC**（不重编码） |
| **动图解析** | GIF / 动图 WebP 直接保存；实况图合成小米动态照片 |
| **预览** | 缩略图点开全屏看大图；视频/音频用系统播放器；下载记录点开看已保存文件 |
| **小米可看的动态图** | 输出**单个 `.jpg`**：JPEG 封面 + 尾部 MP4 + XMP 偏移元数据 |
| **无损** | JPEG / MP4 / AAC 原始字节直接拼接或拷贝，**不重编码**，不需要 FFmpegKit |
| **文件名就是标题** | 取作品标题 → 清 emoji/非法字符 → 折叠空白 → 80 字安全截断 → 重名加后缀 |
| **平台** | 抖音、小红书、任意网页、自填解析端点；知乎未接入 |
| **性能** | `arm64-v8a` 单架构 + release R8 + 流式落盘 + Range 断点续传 + 指数退避 + 多 CDN 直链回退 |
| **抗页面变化** | 公开访客标识 → 爬虫 UA SSR → 普通 UA 三层兜底，概率性空壳自动重试 |

---

## 二、快速开始

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk

cd MotionSaver
./gradlew :core:test                 # 70 个纯 JVM 单元测试
./gradlew :app:assembleDebug         # 产出可安装 APK
python3 verify/verify_v22.py         # 真实网络端到端：两条链接 + 原图下载校验
```

**安装包不走 GitHub**：手机浏览器打开工作台的 `/apk` 直链即可下载安装（需允许「未知来源」）。
minSdk 26 / targetSdk 35 / arm64-v8a / debug 签名。

**在手机上**：把链接或 2~3 条整段口令一起粘贴 → 点「① 解析」→ 每个作品一张卡，缩略图右上角圆圈选中（选中会浮起放大）→ 点图直接预览 → 卡右上角方框一键全选 → 需要时打开「提取音频」→ 点「② 下载选中」。

---

## 三、解析原理与画质

### 3.1 三层取数

1. **公开访客标识（首选）**：向 `ttwid.bytedance.com` 注册一个访客 Cookie（**不需要账号、登录或 a_bogus/x-s 签名**），带该 Cookie 用普通手机 UA 请求用户粘贴的公开分享链接，页面即返回 `window._ROUTER_DATA.videoInfoRes`，含完整四类资源字段。
2. **搜索引擎爬虫 UA（回退）**：平台为 SEO 会 SSR 输出 `schema.org/article` JSON-LD，`image` 数组即图集直链。
3. **普通 UA（兜底）**：`og:*` / 内嵌 JSON 元信息。

平台 SSR 是**概率性返回**（实测同一链接第 1 次常是 36KB 空壳、第 2 次才给 108~132KB 完整数据），因此每种策略都留重试，命中即早停。

### 3.2 为什么不下载「相关推荐」的视频

SSR 页面里会塞几十条相关推荐链接，形如 `https://www.douyin.com/video/7703…`。它们是 **HTML 作品页，不是媒体文件**。旧版因路径里含 `/video/` 被误判成视频资源，下载下来是网页文本，在相册里表现为「0 秒 / 文件损坏」。

v2.2 从根上改掉：
- 首选路径**只精确读取** `item_list[0].images / video.bit_rate / music` 等明确字段，不扫全文链接；
- 兜底路径增加 `PAGE_LINK` 排除规则 + 「必须有媒体扩展名或来自媒体 CDN」白名单。

### 3.3 最高质量原图（实测）

平台为每张图给出多个候选直链（`url_list`），实测同一张图：

| 候选 | 格式 | 体积 | 尺寸 | 说明 |
| --- | --- | --- | --- | --- |
| `~tplv-dy-lqen-new:1080:778:q80.jpeg` | JPEG | **83,390 B** | 1080×778 | ✅ 最高质量，首选 |
| `~tplv-dy-lqen-new:1080:778:q80.webp` | WebP | 41,054 B | 1080×778 | 有损压缩更重 |
| `~tplv-dy-shrink:640:461.webp` | WebP | 22,532 B | 640×461 | ❌ 缩放模板 |
| `download_url_list` 里的 `-water` 变体 | — | 略大 | — | ❌ 带水印 |
| 旧版 JSON-LD 的 `:q75.webp` | WebP | 34,358 B | 1080×778 | 画质更低 |

**注意**：直接改写签名 URL 的模板（例如把 `q75.webp` 改成 `q100.jpeg`）会 **403**——签名绑定完整 URL。因此只能从平台原生给出的候选里挑，这正是访客渲染数据的价值。选取规则：JPEG 优先 > 排除水印 > 排除缩放模板 > 尺寸更大；其余候选留作下载失败时自动回退。

两条用户提供的链接实测结果：

| 链接 | 图集 | 首选 | 体积范围 | 尺寸 | 下载校验 |
| --- | --- | --- | --- | --- | --- |
| `v.douyin.com/KOni4tR0sQQ/` | 4 张 | JPEG | 80~189 KB | 1080×778~1080×1079 | 4/4 可读 |
| `v.douyin.com/nb2psOMhb3U/` | 3 张 | JPEG | 147~217 KB | 1296×1216 | 3/3 可读 |

---

## 四、让视频 / 音频解析跑全：自填解析端点

**图文与原声已可直接用**。公开页通常不声明视频直链，这类资源需要登录态或私有接口。纯公开页路径下 App 会明确提示，不伪造数据。

在「设置 → 高级设置 → 解析端点」填一个**你有合法授权**的数据源（自建服务 / yt-dlp 自托管 / 已授权 API）。App 不预置任何第三方私有接口。

```
https://your.host/parse?url={url}
```

返回 JSON（字段名可在高级设置里改）：

```json
{
  "title": "作品标题", "author": "作者", "caption": "正文", "id": "abc123",
  "images":   ["https://cdn/1.jpg"], "videos": ["https://cdn/v.mp4"],
  "audios":   ["https://cdn/bgm.m4a"], "animated": ["https://cdn/a.gif"],
  "cover": "https://cdn/live_cover.jpg", "live_video": "https://cdn/live.mp4"
}
```

改完端点**不需要重编译**，保存后下次解析即生效。

---

## 五、工程结构

```
MotionSaver/
├── core/                              纯 JVM 模块（无 Android 依赖，可独立测试）
│   └── com/motionsaver/core/
│       ├── MediaModels.kt             资源 / 解析结果模型（含备用直链、画质标签）
│       ├── ParserContract.kt          HttpFetcher(get/post) / MediaParser / 多链接抽取
│       ├── Json.kt                    ★ 零依赖递归下降 JSON 解析器
│       ├── RouterDataParser.kt        ★ 解析 _ROUTER_DATA.videoInfoRes 与画质优选
│       ├── JsonLdMedia.kt             schema.org JSON-LD 结构化数据解析
│       ├── PublicPageParser.kt        三层取页 + 访客标识 + 精确/正则资源发现
│       ├── PlatformParsers.kt        抖音 / 小红书 / 通用网页 + 可插拔注册表
│       ├── EndpointConfig.kt / EndpointParser.kt   自填端点
│       ├── MiniJson.kt / ShareLinkNormalizer.kt / MediaTypeDetector.kt
│       ├── TitleSanitizer.kt / FileNameAllocator.kt
│       ├── MotionPhotoXmp.kt / MotionPhotoWriter.kt / MotionPhotoVerifier.kt
│   └── src/test/resources/            真实抓取的抖音页面 fixture（JSON-LD 版 + 访客渲染版）
├── app/
│   ├── MotionSaverApp.kt              依赖容器 + 预览用 Coil ImageLoader（带 UA/Referer）
│   ├── MainViewModel.kt               多作品状态机、全选、清空、提取音频
│   ├── ui/AppUi.kt                    预览 / 圆形选中 / 浮起放大 / 高级设置二级页
│   └── data/
│       ├── OkHttpFetcher.kt           get + post（注册访客标识）
│       ├── Downloader.kt              多候选直链回退 + Range 续传
│       ├── MediaStoreSaver.kt         四类落盘 + savedUri 供记录预览
│       ├── AudioExtractor.kt / SettingsStore.kt / HistoryStore.kt
│       └── SaveMediaUseCase.kt        解析→勾选→批量下载→合成→命名→落盘
├── verify/verify_live.py              v2.1 JSON-LD 链路验证
├── verify/verify_v22.py               ★ v2.2 访客渲染 + 原图 + 多链接端到端验证
├── samples/                            cover.jpg / clip.mp4 / motion_photo_sample.jpg
└── （APK 二进制不入库，走工作台 /apk 直链）
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

- 同时写经典 `MicroVideo*` 与新版 `MotionPhoto*` / `Container:Directory` 两套字段。
- XMP 段带 4KB padding；重复合成会**替换**已有 XMP 段。
- 未使用小米官方 SDK，按标准自行合成；`core` 内置 `MotionPhotoVerifier` 独立校验。

把 `samples/motion_photo_sample.jpg` 拷到小米相册，应显示为带动态角标的图片、按住可播放 2 秒带声片段。App 内「高级设置 → 运行自检」可在真机复验。

---

## 七、相比现有开源方案的改进点

调研了 [QingZai 轻载](https://github.com/Yukioooooo0/QingZai)（MIT）、[YunX 云析](https://mygit.top/repository/1325498898)（AGPL-3.0）、[DouyinDL](https://github.com/noctiro/DouyinDL)（AGPL-3.0）、抖净等公开项目，主要补齐：

| # | 现有短板 | 本项目的做法 |
| --- | --- | --- |
| 1 | 「动图」多是 mp4/GIF，相册仍当视频 | 按小米 Motion Photo 标准合成**单文件动态照片** |
| 2 | 文件名多为 ID / 时间戳 | 用**作品标题**命名，含完整清洗与重名策略 |
| 3 | 解析器硬编码，接口一变全废 | 可插拔注册表 + 三层取页 + 自填端点 |
| 4 | 只做视频，缺四类统一处理 | 四类资源一次解析、分组展示、按需勾选 |
| 5 | 图文页拿不到图集，或混进推荐视频 | **访客渲染数据精确字段提取** + 页面链接排除 |
| 6 | 图片默认 webp 压缩版 | **自动挑 JPEG 最高档候选**，排除水印与缩放模板 |
| 7 | 单一直链失败就整体失败 | 多 CDN / 多格式候选**自动回退** |
| 8 | 无预览，下完才知道是什么 | 缩略图 + 点开全屏预览 + 下载记录可点开 |
| 9 | 一次只能贴一条链接 | **2~3 条整段口令一起粘贴**，逐个解析 |
| 10 | 设置页堆满技术参数 | 主页面极简，细节收进**高级设置二级页** |
| 11 | 音频只能另找工具 | 平台直链优先，可选从视频**流拷贝抽音轨** |
| 12 | 视频整包载入内存 | 64KB 缓冲流式落盘 + Range 续传 + 重试 |
| 13 | 成品写私有目录，相册扫不到 | 四类内容分别写 **MediaStore** |
| 14 | 缺格式自检 | core 内置 `MotionPhotoVerifier` + 真机自检页 |

---

## 八、合规边界

- **只请求用户粘贴的公开分享链接**，读取页面自身公开的 `og:*` / JSON-LD / `_ROUTER_DATA` 结构化数据；不生成 `a_bogus`、`x-s` 等私有签名，不请求需鉴权的内部接口，不绕过登录墙。
- `ttwid` 是平台给**普通访客**的公开标识（注册端点无需账号），用于让分享页返回浏览器本来就会渲染的公开内容，不属于绕过鉴权。
- 搜索引擎爬虫 UA 只用于触发平台公开的 SEO 服务端渲染输出。
- 公开页若未暴露直链，App 会返回明确警告，**不伪造数据**。需要完整视频/音频能力请接入自己具备合法授权的数据源（第四节）。
- 平台页面结构与 SSR 策略会变，公开页解析可能失效；这是该方案的固有取舍。
- 仅供保存自己有权保存的内容，遵守平台条款与适用法律。

---

## 九、取舍说明

| 原计划 | 实际 | 原因 |
| --- | --- | --- |
| Hilt | 轻量 `MotionSaverApp.Container` | 避免 KSP/kapt 版本耦合 |
| Room + WorkManager | SharedPreferences + StateFlow | 依赖少、包体小 |
| Media3 内置播放器 | 系统播放器 Intent | 预览够用，避免引入大体积播放依赖 |
| FFmpegKit | 未引入 | 无损拼接 / 抽音轨都不需要编解码器，省 40MB |

---

## 十、测试

```bash
./gradlew :core:test
python3 verify/verify_v22.py
```

**70 个 JVM 单元测试，全部通过**，其中 8 个用真实抓取的抖音页面（JSON-LD 版 + 访客渲染版）做 fixture。覆盖：四类资源解析与归类、图集序号命名、音视频/动图识别、自填端点、短链归一、JSON 转义、媒体类型判定、XMP 合成与独立校验、无损拼接、标题清洗、重名分配、实况图识别、无直链告警、注册表可插拔、**访客渲染数据解析、原图画质优选、水印/缩放模板排除、假视频过滤、多链接抽取**。

`verify/verify_v22.py` 另做真实网络端到端：注册访客标识 → 一次识别两条链接 → 解析出 4 张 / 3 张原图 → 全部下载并用 PIL 校验可读 → 输出拼图与 `report_v22.json`。

---

## License

[MIT](LICENSE) · 详见 [DISCLAIMER.md](DISCLAIMER.md)
