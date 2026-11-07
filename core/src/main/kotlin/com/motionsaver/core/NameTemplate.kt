package com.motionsaver.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户可自由排列组合的命名字段。
 *
 * 设计参照通用文件命名实践：把文件名拆成若干「语义 token」，由使用者决定
 * 出现顺序、是否启用、用什么分隔符，而不是把某一种格式写死在代码里。
 * 每个 token 的取值独立清洗，拼装后再整体限长，保证落盘名合法。
 */
enum class NameToken(val key: String, val label: String, val hint: String) {
    TITLE("title", "标题", "作品文案 / 话题"),
    AUTHOR("author", "作者", "口令或页面里的作者名"),
    DATE("date", "日期", "如 20261107"),
    TIME("time", "时间", "如 144523"),
    DATETIME("datetime", "日期时间", "如 20261107_144523"),
    INDEX("index", "序号", "01 / 02，按图集顺序"),
    PLATFORM("platform", "平台", "抖音 / 小红书 / 通用网页"),
    ITEM_ID("item_id", "作品ID", "平台作品编号"),
    SHORT_CODE("short_code", "短码", "6 位稳定去重码"),
    TYPE("type", "类型", "图文 / 视频 / 音频"),
    TOPIC("topic", "话题", "话题词");

    companion object {
        fun of(key: String): NameToken? = entries.firstOrNull { it.key == key }
    }
}

/** 拼装文件名时能拿到的全部上下文；纯 JVM，便于单测与设置页预览复用。 */
data class NameContext(
    val title: String = "",
    val author: String = "",
    val platform: String = "",
    val itemId: String = "",
    val sourceUrl: String = "",
    val typeLabel: String = "",
    val topics: String = "",
    val timeMillis: Long = System.currentTimeMillis(),
    /** 图集序号，从 1 开始；0 表示非图集资源（正文、原声、动图等） */
    val index: Int = 0
)

object NameTemplate {

    /** 默认仍是「标题-短码」：保持 v2.6 的不堆叠覆盖行为不变。 */
    const val DEFAULT_PATTERN = "title|short_code"
    const val DEFAULT_SEPARATOR = "_"

    /** 分隔符白名单：全部是文件名合法字符，避免用户自由输入破坏落盘名。 */
    val SEPARATORS: List<Pair<String, String>> = listOf(
        "_" to "下划线 _",
        "-" to "连字符 -",
        " " to "空格",
        "" to "无",
        "·" to "间隔号 ·"
    )

    /** 常用组合，一键套用后仍可继续增删排序。 */
    val PRESETS: List<Triple<String, String, String>> = listOf(
        Triple("默认 · 不堆叠", DEFAULT_PATTERN, DEFAULT_SEPARATOR),
        Triple("按日期归档", "date|title|index", "_"),
        Triple("按作者归档", "author|date|title|short_code", "_"),
        Triple("时间轴", "datetime|platform|title", "_")
    )

    fun default(): List<NameToken> = listOf(NameToken.TITLE, NameToken.SHORT_CODE)

    /** 容错解析：未知 key 直接丢弃；全空时回落默认模板，绝不产生空文件名。 */
    fun parse(pattern: String): List<NameToken> =
        pattern.split('|').mapNotNull { NameToken.of(it.trim()) }.ifEmpty { default() }

    fun encode(tokens: List<NameToken>): String = tokens.joinToString("|") { it.key }

    /**
     * 按用户排列的顺序生成文件名主干（不含扩展名 / 图集后缀）。
     * 空值 token 自动跳过，避免产生 `标题__作者.jpg` 这种双分隔符。
     */
    fun format(tokens: List<NameToken>, separator: String, ctx: NameContext): String {
        val sep = if (separator in SEPARATORS.map { it.first }) separator else DEFAULT_SEPARATOR
        val parts = tokens.mapNotNull { t ->
            val raw = valueOf(t, ctx)
            if (raw.isBlank()) null
            else TitleSanitizer.sanitize(raw, 40).takeIf { it.isNotBlank() }
        }
        if (parts.isEmpty()) return ""
        val joined = parts.joinToString(sep).trimEnd('.', ' ')
        val max = TitleSanitizer.DEFAULT_MAX_LENGTH
        return if (joined.codePointCount(0, joined.length) <= max) joined
        else joined.substring(0, joined.offsetByCodePoints(0, max)).trimEnd('.', ' ')
    }

    private fun valueOf(t: NameToken, c: NameContext): String = when (t) {
        NameToken.TITLE -> c.title
        NameToken.AUTHOR -> c.author
        NameToken.DATE -> fmt("yyyyMMdd", c.timeMillis)
        NameToken.TIME -> fmt("HHmmss", c.timeMillis)
        NameToken.DATETIME -> fmt("yyyyMMdd", c.timeMillis) + "_" + fmt("HHmmss", c.timeMillis)
        NameToken.INDEX -> if (c.index > 0) String.format(Locale.ROOT, "%02d", c.index) else ""
        NameToken.PLATFORM -> c.platform
        NameToken.ITEM_ID -> c.itemId.take(18)
        NameToken.SHORT_CODE -> FileNameAllocator.shortCode(c.itemId, c.sourceUrl)
        NameToken.TYPE -> c.typeLabel
        NameToken.TOPIC -> c.topics
    }

    private fun fmt(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, Locale.ROOT).format(Date(millis))

    /** 固定样例时间，保证设置页预览不会随系统时间闪烁。 */
    const val SAMPLE_TIME = 1_762_500_000_000L

    fun sample(): NameContext = NameContext(
        title = "崩坏星穹铁道 流萤",
        author = "陌沫",
        platform = "抖音",
        itemId = "7345678901234567890",
        sourceUrl = "https://v.douyin.com/dBhPfcEmyLc/",
        typeLabel = "图文",
        topics = "崩坏星穹铁道 流萤",
        timeMillis = SAMPLE_TIME,
        index = 1
    )

    /** 设置页实时预览；index=0 展示普通文件，index=1 展示图集第一张。 */
    fun preview(tokens: List<NameToken>, separator: String, index: Int = 0): String =
        format(tokens, separator, sample().copy(index = index))
}
