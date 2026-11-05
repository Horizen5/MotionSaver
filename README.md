# 动态存 · MotionSaver 2.0

一个**全新自研、MIT 协议**的 Android 应用：粘贴分享链接 → 一次解析出**图文 / 视频 / 音频 / 动图**四类资源 → 按需勾选下载 → 按**作品标题**命名保存 → 实况图合成为**小米相册可识别的单文件动态照片**。

> 不含任何第三方商业应用的反编译源码，不逆向平台私有签名，不绕过鉴权，不预置第三方解析端点。详见 [DISCLAIMER.md](DISCLAIMER.md)。

---

## 一、功能矩阵

| 需求 | 实现 |
| --- | --- |
| **图文解析** | 图集批量下载（`标题_01.jpg`…），正文另存 `.txt`，作者/标题一并提取 |
| **视频解析** | 视频直链下载，按作品标题命名，写入 `Movies/MotionSaver` |
| **音频解析** | 平台给音频直链则直接下载；没有时可用 `MediaExtractor` 从视频**流拷贝抽 AAC 音轨**（不重编码），输出 `.m4a` |
| **动图解析** | GIF / 动图 WebP 直接保存；实况图（Live Photo）合成小米动态照片 |
| **小米可看的动态图，不是视频** | 输出**单个 `.jpg`**：JPEG 封面 + 尾部追加 MP4 + XMP 偏移元数据。相册显示为带动态角标的图片，长按播放 |
| **无损** | JPEG / MP4 / AAC 原始字节直接拼接或拷贝，**不重编码**，因此不需要 FFmpegKit（省 40MB AAR） |
| **文件名就是视频的名字** | 取作品标题 → 清 emoji/非法字符 → 折叠空白 → 80 字 code point 安全截断 → 重名自动加 `_2` |
| **平台** | 抖音、小红书、**任意网页**（公开页兜底）、**自填解析端点**；知乎未接入 |
| **性能** | `arm64-v8a` 单架构 + release R8 + 64KB 缓冲流式落盘 + Range 断点续传 + 指数退避重试 |
| **交互** | 四 Tab：解析下载（可勾选）/ 下载队列（实时进度）/ 记录（持久化）/ 设置（端点、开关、自检） |

---

## 二、快速开始

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk

cd MotionSaver
./gradlew :core:test                 # 54 个纯 JVM 单元测试
./gradlew :app:assembleDebug         # 产出可安装 APK
./gradlew :app:installDebug          # 装到真机（需 adb 已连接）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（`samples/MotionSaver-2.0.0-debug.apk` 是同一份的交付副本）。

**在手机上**：把链接或整段口令粘贴进输入框 → 点「① 解析」→ 结果按四类分组列出、默认全选 → 取消不想下的 → 点「② 下载选中」。也可从抖音/小红书「分享 → 复制链接」直接把文本送进 App。

---

## 三、让四类解析真正跑全：自填解析端点

**这是最关键的一节。** 平台公开分享页通常只暴露封面图和标题，视频/音频直链只对登录后请求返回。纯公开页路径下，「按标题命名」和「动态照片合成」完全可用，但**视频/音频直链可能拿不到**——此时 App 会明确告警，不会伪造数据。

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

## 四、工程结构

```
MotionSaver/
├── core/                              纯 JVM 模块（无 Android 依赖，可独立测试）
│   └── com/motionsaver/core/
│       ├── MediaModels.kt             平台 / 资源种类 / 四类媒体 / 解析结果模型
│       ├── ParserContract.kt          HttpFetcher / MediaParser 接口、分享口令清洗
│       ├── PublicPageParser.kt        公开页解析基类：三层资源发现 + 自动归类 + 实况检测
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
├── samples/
│   ├── cover.jpg / clip.mp4           真实 JPEG 封面与 H.264/AAC 片段
│   └── motion_photo_sample.jpg        ★ 合成好的动态照片样例，拷到小米手机即可验证
├── app/src/main/assets/selfcheck/     App 内置自检素材
└── （APK 二进制不入库，从仓库 Releases 下载可安装包）
```

---

## 五、动态照片格式说明

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

## 六、相比现有开源方案的改进点

调研了 [QingZai 轻载](https://github.com/Yukioooooo0/QingZai)（MIT）、[YunX 云析](https://mygit.top/repository/1325498898)（AGPL-3.0）、[DouyinDL](https://github.com/noctiro/DouyinDL)（AGPL-3.0）、抖净等公开项目，主要补齐：

| # | 现有短板 | 本项目的做法 |
| --- | --- | --- |
| 1 | 「动图」多是 mp4/GIF，相册仍当视频 | 按小米 Motion Photo 标准合成**单文件动态照片** |
| 2 | 文件名多为 ID / 时间戳 | 用**作品标题**命名，含完整清洗与重名策略 |
| 3 | 标题含 emoji / 非法字符会写入失败 | 白名单清洗 + 汉字间空格消除 + code point 安全截断 |
| 4 | 解析器硬编码，接口一变全废 | **可插拔注册表** + 三层资源发现 + **自填端点**，字段改名也能兜住 |
| 5 | 只做视频，缺图文/音频/动图统一处理 | **四类资源一次解析、分组展示、按需勾选** |
| 6 | 音频只能另找工具 | 平台直链优先，缺失时**视频流拷贝抽音轨**，不重编码 |
| 7 | 合轨 FFmpeg 重编码掉画质 | 容器级**无损拼接**，去掉大体积 AAR |
| 8 | 视频整包载入内存 | 64KB 缓冲流式落盘 + Range 断点续传 + 重试 |
| 9 | 成品写私有目录，相册扫不到 | 四类内容分别写 **MediaStore** 的 Pictures / Movies / Music / Downloads |
| 10 | 缺格式自检 | core 内置 `MotionPhotoVerifier` + App 真机自检页 |
| 11 | 无队列/历史/设置 | 四 Tab：实时队列、持久化记录、设置与端点配置 |

---

## 七、合规边界

- **只请求用户粘贴的公开分享链接**，读取页面自身公开的 `og:*` / 内嵌 JSON 元信息；不生成 `a_bogus`、`x-s` 等私有签名，不请求需鉴权的内部接口。
- 公开页若未暴露直链，App 会返回明确警告，**不伪造数据**。需要完整能力请接入自己具备合法授权的数据源（第三节），下载链路不用改。
- 抖音 / 小红书页面结构会变，公开页解析可能失效；这是该方案的固有取舍。
- 仅供保存自己有权保存的内容，遵守平台条款与适用法律。

---

## 八、取舍说明

为保证「一次编译通过、可安装」，技术栈做了收敛，架构分层与接口保持一致，后续可无缝替换：

| 原计划 | 实际 | 原因 |
| --- | --- | --- |
| Hilt | 轻量 `MotionSaverApp.Container` | 避免 KSP/kapt 版本耦合 |
| Room + WorkManager | SharedPreferences + StateFlow | 依赖少、包体小，接口已隔离 |
| Media3 预览 | 未内置 | 与核心链路无关 |
| FFmpegKit | 未引入 | 无损拼接 / 抽音轨都不需要编解码器，省 40MB |

---

## 九、测试

```bash
./gradlew :core:test
```

**54 个 JVM 单元测试，全部通过**，覆盖：四类资源解析与归类、图集序号命名、音频/动图识别、自填端点映射与优先级、短链归一、极简 JSON 与转义还原、媒体类型判定、XMP 合成与独立校验、无损拼接、重复合成、非法输入拒绝、JPEG 合法性、标题清洗、重名分配、公开页解析、实况图识别、无直链告警、注册表可插拔。

---

## License

[MIT](LICENSE) · 详见 [DISCLAIMER.md](DISCLAIMER.md)
