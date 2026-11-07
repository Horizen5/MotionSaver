# 动态存 · MotionSaver 2.8

一个**全新自研、MIT 协议**的 Android 应用：粘贴分享链接（可一次多条）→ 解析出**图文 / 视频 / 音频 / 动图** → **点开直接预览** → 圆圈选中 → 下载 → 按**可自由组合的命名方案**落盘（默认文案 / 话题 + 短码去重）→ 动图合成为**小米相册可识别的单文件动态照片**。界面为 MIUI / HyperOS 实色风格（纯浅灰底 + 白色实色圆角卡片 + HyperOS 蓝 / 小米橙）。

> 不含任何第三方商业应用的反编译源码，不逆向平台私有签名，不绕过鉴权，不预置第三方解析端点。详见 [DISCLAIMER.md](DISCLAIMER.md)。

**2.8 更新（本次）：整体换成 MIUI / HyperOS 实色风格**

用户真机截图确认：连续多版的「半透明白基底 + 折射 / 菲涅尔高光」液态玻璃，在浅色主题下始终会显出一块与背景不融合的白色方框，底栏还带描边色带；且该作品公开页返回未渲染空壳导致「发现资源 0 个」。本版**彻底换风格**，不再修玻璃，改用 MIUI 的标准实色体系，从结构上消除半透明叠加问题。

| 改动 | 说明 |
| --- | --- |
| **纯实色体系** | 重写 `Glass.kt`：背景为纯浅灰（`#F6F7F9→#EFF1F5`，无彩色光晕 / 焦散 / 壁纸透视），卡片 100% 实色白（深色 `#24272E`），18dp 圆角 + 极淡灰描边 + 柔和阴影；选中态为淡蓝实色底，不再是高光玻璃 |
| **白框 / 分层消失** | 输入框 / 底栏的 `edgeGlass` 从「中心全透 + 白色折射描边」改为和其它卡片一致的**纯白实色圆角卡片**，边界统一；`liquidButton` 去掉顶部弧光与边缘亮线；`highlightBorder` 从白色高光改为极淡中性灰。所有卡片边界一致，白色方框不再是缺陷而是设计本身 |
| **MIUI 配色** | 主色 HyperOS 蓝 `#3485FF` / `#0A6CFF`，次要色小米橙 `#FF6900`（下载按钮），主题色板 `MotionSaverTheme` 同步换色，Button / Switch / Checkbox / Card 等系统组件自动跟随，不再有玻璃与系统控件默认色叠加出的白块 |
| **主题资源** | `windowBackground` 改 `#F4F6F9`、图标底色改 HyperOS 蓝；系统栏保持全透明 + 关闭 contrast scrim，纯浅灰背景连续绘制到物理底部，无水平分层 |
| **解析层零改动** | 本次仅改 6 个 UI / 主题 / 资源文件，`core` 解析层 / 网络层 / ViewModel **一行未动**，解析能力与 v2.7.1 完全一致。截图里「发现资源 0 个」是抖音该图文作品公开页返回未渲染空壳（平台侧），红字已如实标注，可在「设置 → 高级设置 → 解析端点」填入自己的合规数据源 |
| **轻量不增包体** | 全部静态实色绘制、零玻璃零模糊、零新增依赖；release 包 **1,911,428 B ≈ 1.82 MiB**，与 v2.7.1 体积完全相同（去掉了 300+ 行折射绘制代码，内容不同、哈希不同） |

**验证**：122 项 JVM 单测全绿（0 失败 / 0 错误）；`:core:test :app:assembleRelease` BUILD SUCCESSFUL；`aapt` 确认 versionCode 11 / 2.8.0；`apksigner` 证书与 v2.7.1 一致可覆盖安装；dex 内含「2.8.0」「MIUI」，且「液态玻璃」「折射」「菲涅尔」已消失，确认风格确实编入成品。

**2.7.1 更新：白框与底部分层的根因修复 · 真·中心全透**

按用户真机截图复核，2.7.0 声称「白色方框已消失」并不成立。放大截图后确认那块白框是**四个直角、正好等于输入区 padding 之后的矩形**，不是控件自带容器；联网调研 Android 官方 edge-to-edge / system-bars 指南与多篇实践后，两个问题的真正根因如下。

| 问题 | 真正根因 | 修复 |
| --- | --- | --- |
| **输入框直角白框** | 2.7.0 虽已把 `OutlinedTextField` 换成 `BasicTextField`（dex 反编译确认旧类确实不在），但输入区外层仍调用 `glassSurface`，其 `baseBrush` 会铺一层 `0x52FFFFFF` 半透明白基底；叠上 `decorationBox` 容器与 `shadow`，在浅色渐变上形成一块边界清晰的独立亮矩形 | 新增 `edgeGlass`：**中心 100% 透明**，只在轮廓内侧画折射柔光带 + 镜面高光 + 色散（`drawRoundRect` + `Stroke` 被 `clip` 裁掉外半）；去掉 `glassSurface` / `decorationBox` / 白基底，占位文字由外层 `Box` 直接绘制，`BasicTextField` 显式 `background(Color.Transparent)`。从结构上不可能再出现铺白矩形 |
| **底部明显分层** | `themes.xml` 的 `windowBackground = #FFFAFAFE` 近白实色 + `enableEdgeToEdge()` 默认把 `isNavigationBarContrastEnforced = true`，系统在导航栏区强制盖一层浅色对比度 scrim，与 App 上方连续渐变横切出一条水平色带 | 官方方案：`SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)` 让状态栏 / 导航栏全透明；在 `enableEdgeToEdge()` **之后**设 `window.isNavigationBarContrastEnforced = false`（该函数内部会置 true，顺序不能反）；`WindowCompat.setDecorFitsSystemWindows(false)`；主题加透明 `navigationBarColor` / `statusBarColor` 与 `enforceNavigationBarContrast=false`；`windowBackground` 改为与渐变起始色一致的 `#F4F6FE`；manifest 加 `adjustResize`。整屏渐变由此连续绘制到物理底部 |
| **底栏同材质** | 底栏胶囊仍用铺白基底的 `glassSurface`，与背景有色差 | 改用 `edgeGlass`，仅保留极淡 `centerTint`（浅色 7% / 深色 4%）保证可读性，阴影由 16dp 降到 10dp |
| **零开销不增包体** | — | 全部 `drawWithCache` 静态绘制、画笔按 size 缓存、零逐帧模糊、零新增依赖；release 包 **1,911,428 B**，比 2.7.0 仅多 84 B |

**验证**：122 项 JVM 单测全绿（0 失败 / 0 错误）；`:core:test :app:assembleRelease` BUILD SUCCESSFUL；`aapt` 确认 versionCode 10 / 2.7.1、manifest 含 `adjustResize(0x10)`；`apksigner` 证书与 v2.7.0 一致可覆盖安装；dex 内含 `setNavigationBarContrastEnforced` / `setDecorFitsSystemWindows` 调用，确认运行时关闭 scrim 已编入成品。

**2.7 更新：真·液态玻璃 · 命名方案自由组合**

按用户截图反馈重做玻璃材质，并把命名方式从写死改成可由用户排列组合。联网调研了 Apple HIG Materials / WWDC25 219、Haze 2.1、AGSL SDF 折射等开源方案，结论是：液态玻璃的核心是**只在轮廓边缘折射光线、中心几乎清澈 + 沿轮廓的镜面高光 + 菲涅尔内发光 + 淡色散**，而不是均匀半透明白填充 —— 旧版整块 80% 白正是截图里「白色方框」的根因。

| 改动 | 说明 |
| --- | --- |
| **液态玻璃材质** | 重做 `Glass.kt`：`drawWithCache` 缓存画笔，叠加「边缘折射透镜带（中心全透）→ 左上淡青 / 右下淡粉色散 → 底部内阴影（厚度）→ 斜向镜面高光描边」四层；基底白降到 18~32%，背景彩色光晕与斜向焦散亮带透出来 |
| **不引入大依赖** | Haze / AGSL / RenderEffect 均需 API 31+ 或逐帧 GPU 离屏缓冲，minSdk 26 会退化成 scrim 且掉帧；本版全部静态绘制、零新增依赖、零逐帧模糊，`drawWithCache` 保证 size 不变时画笔零重建 |
| **白色方框（未彻底解决，2.7.1 修复）** | 本版把 `OutlinedTextField` 换成 `BasicTextField`，但外层 `glassSurface` 的半透明白基底仍在，真机上白框依旧存在；根因与彻底修复见上方 2.7.1 |
| **底栏不再贴底** | 加 `navigationBarsPadding()` + 底部 24dp 悬浮间距，避开手势条 |
| **按钮同材质** | 主按钮加顶部弧形高光 + 边缘亮线，与卡片同属液态玻璃，不再是实心色块 |
| **命名方案可组合** | 「设置 → 命名方案」独立页：日期、时间、日期时间、序号、标题、作者、平台、作品 ID、短码、类型、话题共 11 个字段，自由勾选 / 上下排序 / 删除，5 种分隔符，4 组快捷预设，实时预览单文件与图集文件名 |
| **不堆叠仍默认** | 默认模板仍是 `标题 + 短码`；含「序号」字段时由模板决定序号位置且不再追加 `_01`，避免双序号；空字段自动跳过，结果统一清洗限长 |
| **作者可提取** | 公开页拿不到作者时，从分享口令模板 `【某某的图文作品】` 提取，配合「按作者归档」预设 |
| **验证** | 122 项 JVM 单测全绿（新增 14 项 `NameTemplateTest`，0 失败 / 0 错误）；release 构建成功，包体仍 < 10 MB |

**2.6 更新：不再误报动图 · 命名不再堆叠 · HyperOS 柔光玻璃界面**

用户反馈「这个作品其实并没有 GIF 动态图，软件却识别到有」以及「很多图片重叠到一块」，本次逐一修正，并整体换 UI。

| 改动 | 说明 |
| --- | --- |
| **单图不再误报动图** | 根因：旧版只要「有图」就生成一条本地合成动图，单张静态图也被标成「动图·实况动态」；且单图实况被标成 `LIVE_PHOTO`（标签恰好叫「动态图」）。现在**只有 ≥2 张静态帧才做「轮播动图（合成）」**，单图作品一律回归【图文】，明确提示「本作品不含动图」 |
| **命名用文案 / 话题** | 旧版把 `#话题` 全删空、多条口令又只传纯 URL，标题全部退化成硬编码「抖音作品」。现在从分享口令提取话题（如「崩坏星穹铁道 流萤」）/ 文案作为标题，页面有真实文案时以页面为准 |
| **文件名去重不堆叠** | 落盘名追加作品 ID 稳定短码（`标题-6位短码`），同一作品恒定、不同作品必不同，叠加 `_01` 图集序号；UI 显示仍是干净标题 |
| **HyperOS / MIUI 风格** | 大圆角（卡片 26dp / 磁贴 20dp）、胶囊按钮与胶囊底栏、蓝紫通透色板、字重字号跳跃 |
| **iOS 质感柔光玻璃** | 背景彩色光晕 + 半透明渐变玻璃面 + 顶部高光描边 + 柔和大阴影，≥2 层玻璃层次 |
| **零开销玻璃 / 包体不增** | 玻璃是**静态渐变绘制**，不用 `Modifier.blur` / Haze 等实时 GPU 模糊（低版本无效、滚动掉帧、增大包体）。零新增依赖，release 包仍约 1.8 MB，稳在 10 MB 内 |
| **验证** | 108 项 JVM 单测全绿（0 失败 / 0 错误，新增 9 项：话题命名、通用词过滤、短码稳定/区分、多条口令切分） |

**2.5 更新：动态照片可以直接点开播放**

对照用户上传的真机样本 `MVIMG_20261106_230518.jpg`（5,983,189 B，3060×4080 封面 + 2,990,126 B 内嵌 MP4，H.264 1080×1440 / 30fps / 45 帧 / 1.968 s + AAC）重写了动态照片的读取与预览链路。

| 改动 | 说明 |
| --- | --- |
| **动图预览点开就动** | 解析结果里点「动图」卡片即按静态帧轮播（整轮 2~4 秒），**只播一次、播完自动暂停停在最后一帧、不循环**；可点画面或「↻ 再播一次」重看 |
| **App 内播放本地动态照片** | 「记录」页里 `MVIMG_*` / 含「动态照片」的文件，读 XMP 定位尾部 MP4 → `MediaPlayer.setDataSource(fd, offset, length)` 从**原文件偏移处**无损播放，同样播完暂停 |
| **真机 XMP 兼容** | 补上 `GCamera:MicroVideo="1"`（真机样本有、旧版缺），小米相册才认；同时保留 1.0 的 `Container:Item:Length` |
| **两代相册命名兼容** | 产物改为 `MVIMG_标题-动态照片MP.jpg`：`MVIMG_` 前缀（老相册）+ 扩展名前 `MP` 结尾（Android 1.0 正则） |
| **元数据被剥也能救** | 新增 `MotionPhotoReader`：优先 `MicroVideoOffset` / `Item:Length`，缺失时扫描 `ftyp` box 兜底；纯 JVM，无需第三方 XMP 库 |
| **无损** | 全程只做字节定位与拼接，封面 JPEG 与视频 MP4 都不重新编码；微信转发会剥 XMP，需走文件/原图传输 |

实现原理、能用什么播放、手机上怎么播，详见 [MOTION_PHOTO.md](MOTION_PHOTO.md)。

**2.4 更新：真正解决「识别不了动图」**

旧版只是给实况图作品打了个标签、提示「去高级设置接端点补齐」，所以「动图」分组永远是空的——这正是反复识别不到动图的原因。本版改为**不再等平台给动态视频，直接在设备本地合成**。

| 改动 | 说明 |
| --- | --- |
| **动图成为可下载资源** | 图文 / 实况图作品一律产出一条 `动图` 资源，出现在「动图」分组，可勾选、可下载、可预览，不再只是空标签 |
| **根因实测** | 抖音公开分享页 `_ROUTER_DATA` 里 `play_addr` 是 `ftypM4A` **纯音频**、`bit_rate=null`、`image_infos=null`；6 种 UA / 6 种页面路径 / 6 个无签名公开接口均未暴露动态 MP4，带签名的私有接口不接触 |
| **本地合成轮播 GIF** | 用公开页合法取得的静态帧，在设备上编码成真正的 GIF89a 多帧动图（LZW + Floyd–Steinberg 抖动量化 + Netscape 无限循环），任何相册都能看会动；纯 JVM 实现、零第三方依赖。已修正 LZW **early-change** 码长增长（须 `next > 2^codeSize`，写成 `>=` 会让严格解码器报 `broken data stream`），并用 JDK ImageIO 与 Pillow 两套解码器逐帧验证 |
| **本地合成小米动态照片** | `MediaCodec` 硬编码 H.264 轮播片段 → `MotionPhotoWriter` 与首帧原图 JPEG 无损拼接（XMP + 尾部 MP4），小米相册可识别播放；设备不支持时自动降级为只出 GIF，绝不写坏文件 |
| **不伪造** | 绝不把 M4A 音频伪装成视频（那是相册里「0 秒 / 损坏」的根源）；静态帧缺失时明确报错而非生成半成品 |
| **互不影响** | 轮播动图是附加产物：图文仍显示【图文】、静态图仍按 `标题_01.jpg` 编号命名、原声仍可单独下载 |
| **2.6 收敛** | 上面「本地合成 GIF」能力现在**只对 ≥2 张静态帧的多图作品提供**：单张静态图没有第二帧，作品本身也不含动态片段，旧版对它生成动图属于假识别。UI 分组改名为「轮播动图（合成）」，【动态图】误导标签改为【实况图】，单图作品明确提示「不含动图」 |
| **验证** | 108 项 JVM 单测全绿（0 失败 / 0 错误）；真实「沙龙月季」3 帧原图合成的 GIF 经 Pillow 严格逐帧解码通过（3 帧 / 3.6 s / 循环 0 / 三帧互不相同）；真机样本 `MVIMG_20261106_230518.jpg` 由**实际编译产物**解析出 `videoOffset=2993063 / videoLength=2990126 / 时间戳 910229`，与 Python 二进制实测一致 |

> 最终安装包：`MotionSaver-2.8.0-release.apk`（versionCode 11 / versionName 2.8.0，沿用 debug keystore 签名，可直接覆盖安装旧版），**1,911,428 B ≈ 1.82 MiB**，SHA-256 `bb8b66fadf506665091c961af702e2415ae0d805250d97492b4b5fce5e6587ac`，详见下方「交付」。APK 只走直链分发，不上传 GitHub。

> 关于「60Hz」：Android 没有强制设置刷新率的公开 API（刷新率由系统与厂商显示策略管理），本版做的是向系统**请求**设备支持的最高刷新率档位，并消除掉帧来源（JIT、全量重组、大图解码 GC）。系统不允许时会静默回退，不影响功能。

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
| **命名方案自由组合** | 设置页可勾选日期 / 时间 / 序号 / 标题 / 作者 / 平台 / 作品 ID / 短码 / 类型 / 话题并排序，5 种分隔符、4 组预设、实时预览；默认仍带稳定短码不堆叠 |
| **平台** | 抖音、小红书、任意网页、自填解析端点；知乎未接入 |
| **性能** | `arm64-v8a` 单架构 + release R8 全量 + Baseline/Startup Profile AOT + 高刷新率请求 + LazyColumn 按需组合；流式落盘 + Range 断点续传 + 指数退避 + 多 CDN 回退 |
| **抗页面变化** | 公开访客标识 → 爬虫 UA SSR → 普通 UA 三层兜底，概率性空壳自动重试；`/share/slides/` 自动归一化到 `/share/note/` |

---

## 二、快速开始

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk

cd MotionSaver
./gradlew :core:test                 # 122 个纯 JVM 单元测试
./gradlew :app:assembleDebug         # 产出可安装 APK
python3 verify/verify_v22.py         # 真实网络端到端：两条链接 + 原图下载校验
```

**安装包不走 GitHub**：手机浏览器打开工作台上传页，点顶部「下载并安装 APK」（即 `/apk` 直链）即可下载安装（需允许「未知来源」）。直链带访问凭据 `?sharecode=...`，完整入口在交付消息中给出，不写进公开仓库。
minSdk 26 / targetSdk 35 / arm64-v8a / debug 签名。

### 交付（v2.8.0 最终产物）

| 项 | 值 |
| --- | --- |
| 文件 | `MotionSaver-2.8.0-release.apk` |
| 版本 | versionCode 11 / versionName 2.8.0 |
| 体积 | 1,911,428 B（1.82 MiB / 1.911 MB），**< 10 MB** |
| SHA-256 | `bb8b66fadf506665091c961af702e2415ae0d805250d97492b4b5fce5e6587ac` |
| 签名 | `CN=Android Debug`，证书 SHA-256 `ed2cd24a6da533c8a9d4541dc83ab96936e51072e0298f0b6b601356c3f32d9d`，与 v2.7.1 一致，可直接覆盖安装 |
| 校验 | `aapt dump badging` 确认包名 `com.motionsaver.app` / versionCode 11 / 2.8.0；`apksigner verify` 通过；dex 内含「2.8.0」「MIUI」「命名方案」，旧版「液态玻璃 / 折射 / 菲涅尔」字符串已消失 |
| 下载 | 工作台 `/apk` 直链（带 sharecode，见交付消息），**不上传 GitHub** |

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

**122 个 JVM 单元测试，全部通过（0 失败 / 0 错误）**，其中 8 个用真实抓取的抖音页面（JSON-LD 版 + 访客渲染版）做 fixture。覆盖：四类资源解析与归类、图集序号命名、音视频/动图识别、自填端点、短链归一、JSON 转义、媒体类型判定、XMP 合成与独立校验、无损拼接、标题清洗、重名分配、实况图识别、无直链告警、注册表可插拔、**访客渲染数据解析、原图画质优选、水印/缩放模板排除、假视频过滤、多链接抽取**，以及新增的 **GIF89a 头尾 / 逐帧解码 / 大渐变字典溢出 / 异长宽比 / 空帧拒绝、动图资源产出与静态图编号隔离、实况单图与「沙龙月季」三图回归**，2.5 的 **动态照片布局解析（真机样本 XMP、只读头部、Item:Length、ftyp 兜底、非动态照片拒绝）与 `MVIMG_…MP.jpg` 命名兼容**，以及 2.6 的 **单图不误报动图、话题/文案语义化命名、短码去重、多条口令切分、命名合法性**，以及 2.7 的 **命名方案自由组合（字段顺序 / 分隔符 / 空值跳过 / 序号 / 短码 / 预览）与口令模板作者提取**。

`verify/verify_v22.py` 另做真实网络端到端：注册访客标识 → 一次识别两条链接 → 解析出 4 张 / 3 张原图 → 全部下载并用 PIL 校验可读 → 输出拼图与 `report_v22.json`。

---

## License

[MIT](LICENSE) · 详见 [DISCLAIMER.md](DISCLAIMER.md)
