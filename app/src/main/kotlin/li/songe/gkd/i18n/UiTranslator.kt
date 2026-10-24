package li.songe.gkd.i18n

/** Presentation only: never translate selectors, stored rules, OCR input or action evidence. */
class UiTranslator(private val entries: Map<String, String>) {
    private data class Template(val regex: Regex, val english: String, val verbatim: Set<Int>)
    private val rawSnapshot = "{0}. {1}\n文字  {2}\n描述  {3}\nID  {4}\n区域  {5}\n可点击  {6} · 父级可点击  {7}"
    private fun verbatimSlots(source: String): Set<Int> = when (source) {
        rawSnapshot -> setOf(2, 3, 4, 5)
        "文件：{0}", "应用：{0}", "诊断代码：{0}", "选择器：{0}" -> setOf(0)
        "将匹配：{0} / {1}" -> setOf(0, 1)
        else -> emptySet()
    }
    private val slot = Regex("\\{(\\d+)}")
    private val templates by lazy {
        entries.filterKeys { slot.containsMatchIn(it) }.entries
            .sortedByDescending { slot.replace(it.key, "").length }
            .map { (source, english) ->
                var end = 0
                val expression = StringBuilder("\\A")
                slot.findAll(source).forEach { match ->
                    expression.append(Regex.escape(source.substring(end, match.range.first)))
                    expression.append("([\\s\\S]*?)")
                    end = match.range.last + 1
                }
                expression.append(Regex.escape(source.substring(end))).append("\\z")
                Template(Regex(expression.toString()), english, verbatimSlots(source))
            }
    }

    fun translate(text: String): String = translate(text, 0)

    private fun translate(text: String, depth: Int): String {
        if (depth > 4) return text
        entries[text]?.takeUnless { slot.containsMatchIn(text) }?.let { return it }
        if (text.none { it in '\u3400'..'\u9fff' }) return text
        // App names, rule text and unknown user content are preserved verbatim.
        for (template in templates) {
            val match = template.regex.matchEntire(text) ?: continue
            return slot.replace(template.english) { token ->
                val index = token.groupValues[1].toInt() + 1
                val value = match.groupValues.getOrElse(index) { token.value }
                if (index - 1 in template.verbatim) value else translate(value, depth + 1)
            }
        }
        return text
    }
}
