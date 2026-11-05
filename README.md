# 动态存 · MotionSaver

一个**全新自研、MIT 协议**的 Android 示例应用：粘贴抖音 / 小红书分享链接，按**作品标题**命名保存，并把实况图合成为**小米相册可识别的单文件动态照片**。

> 本项目不含任何第三方商业应用的反编译源码，不逆向任何平台私有签名，不绕过鉴权，不预置第三方解析端点。详见 [DISCLAIMER.md](DISCLAIMER.md)。

---

## 一、它解决什么

| 需求 | 实现 |
| --- | --- |
| 动态图是「小米可查看的动态照片」，不是一段视频 | 输出**单个 `.jpg`**：JPEG 静态封面 + 尾部追加 MP4 + XMP 偏移元数据。相册里显示为一张带动态角标的图片，按住/点播放键才动 |
| 无损 | JPEG / MP4 原始字节直接拼接，**不重编码**，画质零损失；因此不需要 FFmpegKit（省掉 40MB AAR） |
| 下载时文件名就是视频的名字 | 取作品标题 → 清洗 emoji/非法字符 → 折叠空白 → 80 字截断 → 重名自动加 `_2` |
| 视频解析 | 可插拔解析器，先抖音、后小红书，知乎暂停 |
| 性能最好 | `arm64-v8a` 单架构 + release R8 + 流式落盘（不整包入内存）+ Range 断点续传 |

---

## 二、快速开始

```bash
# 环境：JDK 17、Android SDK（API 35 / Build Tools 35）
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk

cd MotionSaver
./gradlew :core:test                 # 24 个纯 JVM 单元测试
./gradlew :app:assembleDebug         # 产出可安装 APK
./gradlew :app:installDebug          # 装到真机（需 adb 已连接）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

国内网络已配好镜像加速：Gradle 发行版走腾讯云，Maven 依赖走腾讯 maven-public 镜像。

---

## 三、工程结构

```
MotionSaver/
├── core/                         纯 JVM 模块（无 Android 依赖，可独立测试）
│   └── com/motionsaver/core/
│       ├── MediaModels.kt        平台 / 媒体类型 / 解析结果数据模型
│       ├── TitleSanitizer.kt     文件名清洗（emoji / 非法字符 / 空白 / 截断）
│       ├── FileNameAllocator.kt  按标题分配文件名 + 重名去重
│       ├── MotionPhotoXmp.kt     Motion Photo XMP 元数据构造
│       ├── MotionPhotoWriter.kt  JPEG + MP4 → 单文件动态照片（无损）
│       ├── MotionPhotoVerifier.kt 独立校验器（自检页 / 测试用）
│       ├── ParserContract.kt     HttpFetcher / MediaParser 接口、分享口令提取
│       ├── PublicPageParser.kt   公开页解析基类（og:* / JSON-LD）
│       └── PlatformParsers.kt    抖音 / 小红书解析器 + 可插拔注册表
├── app/                          Android Compose 应用
│   └── com/motionsaver/app/
│       ├── MotionSaverApp.kt     轻量依赖容器
│       ├── MainActivity.kt       单 Activity + Share Intent 接收
│       ├── MainViewModel.kt      UI 状态与任务编排
│       ├── data/
│       │   ├── OkHttpFetcher.kt  core 网络接口实现
│       │   ├── Downloader.kt     流式下载 + Range 断点续传
│       │   ├── MediaStoreSaver.kt 写入系统相册（小米相册可识别）
│       │   └── SaveMediaUseCase.kt 解析→下载→合成→命名→保存
│       └── ui/                   Compose 三个页面（解析 / 记录 / 设置）
└── samples/
    ├── cover.jpg                 真实 JPEG 封面
    ├── clip.mp4                  真实 H.264/AAC 2 秒片段
    └── motion_photo_sample.jpg   ★ 合成好的动态照片样例，可直接拷到小米手机验证
```

---

## 四、动态照片格式说明

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

- 同时写经典 `MicroVideo*` 与新版 `MotionPhoto*` / `Container:Directory` 两套字段，覆盖不同系统版本的相册实现。
- XMP 段带 4KB padding，方便相册后续原地改写元数据而不移动视频数据。
- 重复合成时会**替换**已有 XMP 段，不会叠加（有单元测试覆盖）。
- 未使用小米官方 SDK（需申请账号、限 HyperOS + 大陆机型），按标准自行合成，无门槛。

**真机验证**：把 `samples/motion_photo_sample.jpg` 拷到小米手机相册，应显示为一张带动态照片角标的图片，按住可播放 2 秒带声音的片段。本仓库已用独立 Python 解析器校验：XMP 偏移 72220 与尾部 MP4 长度精确一致、ftyp box 正确、JPEG 段 EOI 完整、尾部字节与源 MP4 逐字节相同。

---

## 五、相比现有开源方案的改进点

调研了 [QingZai 轻载](https://github.com/Yukioooooo0/QingZai)（MIT）、[YunX 云析](https://mygit.top/repository/1325498898)（AGPL-3.0）、[DouyinDL](https://github.com/noctiro/DouyinDL)（AGPL-3.0）、抖净等公开项目，主要补齐：

| # | 现有短板 | 本项目的做法 |
| --- | --- | --- |
| 1 | 「动图」多是 mp4/GIF，相册仍当视频 | 按小米 Motion Photo 标准合成**单文件动态照片** |
| 2 | 文件名多为 ID / 时间戳 | 用**作品标题**命名，且做了完整的清洗与重名策略 |
| 3 | 标题含 emoji / `/` `:` 等非法字符会写入失败 | 白名单清洗 + 汉字间空格消除 + code point 安全截断 |
| 4 | 解析器硬编码，接口一变全废 | **可插拔注册表**，新增平台只实现一个 `MediaParser` |
| 5 | 合轨 FFmpeg 重编码导致画质损失 | 容器级**无损拼接**，不重编码，也去掉了大体积 AAR |
| 6 | 视频整包载入内存 | 64KB 缓冲**流式落盘** + Range 断点续传 |
| 7 | 成品写私有目录，相册扫不到动态照片 | 直接写 **MediaStore 相册** `Pictures/MotionSaver` |
| 8 | 缺格式自检 | core 内置独立 `MotionPhotoVerifier`，合成后强制校验 |
| 9 | 缺合规边界与免责 | 明确的 `DISCLAIMER.md`、App 内提示、解析失败不伪造数据 |

---

## 六、合规边界（重要）

- **只请求用户粘贴的公开分享链接**，读取页面自身公开的 `og:*` / JSON-LD 元信息；不生成 `a_bogus`、`x-s` 等私有签名，不请求需鉴权的内部接口。
- 公开页若**未暴露直链**，程序会返回明确警告并停止，**不会伪造数据**。若需要完整能力，请自行接入具备合法授权的数据源（把 `MediaParser` 实现换掉即可，下载链路不变）。
- 抖音 / 小红书页面结构会变，公开页解析**可能失效**，这是该方案的固有取舍。
- 仅供个人学习研究与保存自己有权保存的内容，请遵守平台条款与著作权法。

---

## 七、取舍说明

为保证「一次编译通过、可安装」，对技术栈做了两处收敛，架构分层与接口保持一致，后续可无缝替换：

| 原计划 | 实际 | 原因 |
| --- | --- | --- |
| Hilt | 轻量 `MotionSaverApp.Container` | 避免 KSP/kapt 版本耦合，减少构建环节 |
| Room + WorkManager | ViewModel StateFlow 内存记录 | MVP 阶段无需持久化；接口已隔离 |
| Media3 预览 | 未内置 | 与核心链路无关，可后置 |
| FFmpegKit | 未引入 | 无损拼接不需要编解码器，省 40MB |

---

## 八、测试

```
./gradlew :core:test
```

24 个测试，覆盖：XMP 合成与独立校验、无损拼接、重复合成、非法输入拒绝、JPEG 合法性、标题清洗（emoji / 非法字符 / 空白 / 截断 / 空值回退）、重名分配、分享口令提取、平台识别、公开页解析、实况图识别、无直链告警、注册表可插拔。

---

## License

[MIT](LICENSE) · 详见 [DISCLAIMER.md](DISCLAIMER.md)
