package com.motionsaver.core

/**
 * 作品命名：把「分享口令 / 作品文案」变成语义化、可区分的标题。
 *
 * 背景：抖音 / 小红书图文作品页面可见文字常常只有话题标签
 * （如 `# 崩坏星穹铁道 # 流萤`）。旧版把 `#xxx` 全部删掉后标题变空，回退成
 * 硬编码的「抖音作品」，于是不同作品落盘时都叫 `抖音作品_01.jpg`，在相册里
 * 堆叠、互相覆盖 —— 就是用户说的「很多图片重叠到一块」。
 *
 * 遵循通用文件命名实践（语义化主体 → 清洗非法字符 → 长度限制 → 稳定短码去重）：
 *  - 本类负责「语义化主体」：优先话题词、其次清洗后的正文；
 *  - 唯一短码去重放在 [FileNameAllocator.scopedTitle]（落盘层），保证
 *    UI 标题干净好看、文件名却全局唯一，不同作品不会互相覆盖。
 *
 * 纯 JVM，不依赖 Android，便于单测。
 */
object WorkTitle {

    private val URL = Regex("""https?://[^\s"'<>\p{Cntrl}]+""")
    private val NOISE = Regex(
        """[0-9.]+\s*复制打开抖音|复制打开抖音|复制此链接|打开[Dd]ouyin|打开抖音|打开小红书|看看|发布在小红书|长按复制|复制口令|去抖音|戳这里|关注我|抖音，记录美好生活|记录美好生活|已经收获了|来抖音"""
    )
    /** `【某某的图文作品】` 是分享口令模板（作者名），不是作品文案。 */
    private val TEMPLATE_BRACKET =
        Regex("""【[^】]{0,30}的(?:图文作品|视频作品|图文|作品)】|\[[^\]]{0,30}的(?:图文作品|视频作品|图文|作品)\]""")
    /** 口令尾部的时间 / 随机码（`12/17 :0pm VYm:/`、`8.43 ...`）一并截掉。 */
    private val TAIL_FROM_CLOCK = Regex("""\d{1,2}[/:]\d{2}.*$""", RegexOption.DOT_MATCHES_ALL)
    private val LEADING_CODE = Regex("""^\s*\d+(?:\.\d+)?\s+""")
    /** 一个「词块」：连续的中 / 日 / 韩文字与字母数字。 */
    private val PHRASE = Regex("""[\u4E00-\u9FFF\u3040-\u30FF\uAC00-\uD7AFA-Za-z0-9]+""")

    private val GENERIC_KEYS = listOf(
        "抖音", "记录美好生活", "发布在", "小红书", "网页作品", "抖音作品", "小红书笔记",
        "已经收获", "来抖音", "untitled", "作品分享"
    )
    private val GENERIC_EXACT =
        setOf("", "抖音", "作品", "图文", "视频", "笔记", "分享", "图文作品", "视频作品", "untitled")

    /** 站点通用词 / 页面模板标题，不该被当成作品名。 */
    fun isGeneric(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t in GENERIC_EXACT || t.length < 2) return true
        // 含平台模板词且几乎没有其它实质词块 → 判为通用
        return GENERIC_KEYS.any { t.contains(it) } && PHRASE.findAll(t).count() <= 2
    }

    /**
     * 提取话题词拼成可读名字（`# 崩坏星穹铁道 # 流萤` → `崩坏星穹铁道 流萤`）。
     * 无话题返回空串。
     */
    fun topics(text: String): String {
        if (!text.contains('#')) return ""
        val names = strip(text).split('#').drop(1).mapNotNull { segment ->
            PHRASE.find(segment)?.value
                ?.takeIf { it.length >= 2 && !it.all(Char::isDigit) && !isGeneric(it) }
        }.distinct().take(4)
        return names.joinToString(" ").trim()
    }

    /** 从完整分享文本生成标题：话题优先，其次清洗后的正文；都不有效则返回空。 */
    fun fromShare(text: String): String {
        val t = topics(text)
        if (t.isNotBlank() && !isGeneric(t)) return sanitize(t)
        val body = bodyOf(text)
        return if (body.isNotBlank() && !isGeneric(body)) sanitize(body) else ""
    }

    /**
     * 从分享口令模板里取作者名：`【陌沫つ゛的图文作品】` → `陌沫つ゛`。
     * 公开页常常拿不到 author 结构化字段，口令模板是最稳的来源。
     */
    fun authorOf(text: String): String {
        val m = Regex("""【([^】]{1,30})的(?:图文作品|视频作品|图文|作品)】""").find(text)
            ?: Regex("""\[([^\]]{1,30})的(?:图文作品|视频作品|图文|作品)\]""").find(text)
        val name = m?.groupValues?.getOrNull(1).orEmpty().trim()
        return if (name.length >= 2 && !isGeneric(name)) sanitize(name) else ""
    }

    /** 清洗后的正文（去链接 / 口令模板 / 时间随机码 / 话题符号）。 */
    fun bodyOf(text: String): String {
        val s = strip(text).replace('#', ' ')
        return PHRASE.findAll(s)
            .map { it.value }
            .filter { w -> w.length >= 2 && !w.all(Char::isDigit) && !isGeneric(w) }
            .distinct()
            .take(6)
            .joinToString(" ")
            .trim()
    }

    private fun strip(text: String): String = text
        .replace(URL, " ")
        .replace(TEMPLATE_BRACKET, " ")
        .replace(NOISE, " ")
        .replace(TAIL_FROM_CLOCK, " ")
        .replace(LEADING_CODE, " ")
        .replace(Regex("""[\p{Cntrl}]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun sanitize(s: String): String = TitleSanitizer.sanitize(s, 40)
}
