package li.songe.gkd.i18n

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UiTranslatorTest {
    private fun catalog(): Map<String, String> {
        var root = File(System.getProperty("user.dir")!!)
        while (!File(root, "settings.gradle.kts").exists()) root = root.parentFile!!
        return Json.decodeFromString(File(root, "app/src/main/assets/ui_en.json").readText())
    }

    @Test fun placeholders_are_preserved_and_may_be_reordered() {
        val t = UiTranslator(mapOf("应用 {0}：{1}" to "{1} in {0}"))
        assertEquals("42 in com.example", t.translate("应用 com.example：42"))
    }
    @Test fun nested_labels_are_translated_without_rewriting_unknown_content() {
        val t = UiTranslator(catalog())
        assertEquals("Notifications · Normal", t.translate("通知管理 · 正常"))
        assertEquals("诊断数据 private $42", t.translate("诊断数据 private $42"))
        assertEquals("com.example.中文应用", t.translate("com.example.中文应用"))
    }
    @Test fun multiline_records_keep_values_in_their_own_fields() {
        val t = UiTranslator(catalog())
        assertEquals("Source: Local import\nRule version: 572\nApps covered: 3\nDedicated groups: 4\nRules: 5\nSHA256: abc\nUpdated: today",
            t.translate("来源：本地导入\n规则版本：572\n覆盖应用：3\n专用规则组：4\n规则：5\nSHA256：abc\n更新时间：today"))
    }
    @Test fun every_catalog_entry_keeps_its_placeholder_set_and_is_english() {
        val token = Regex("\\{\\d+}")
        val data = catalog()
        assertTrue(data.size >= 980)
        for ((source, english) in data) {
            assertFalse("Empty translation: $source", english.isBlank())
            assertFalse("Chinese output: $source", english.any { it in '\u3400'..'\u9fff' })
            assertEquals(source, token.findAll(source).map { it.value }.toList().sorted(),
                token.findAll(english).map { it.value }.toList().sorted())
        }
    }
    @Test fun raw_control_evidence_and_file_names_are_not_translated() {
        val t = UiTranslator(catalog())
        val original = "1. 候选控件\n文字  关闭\n描述  同意\nID  com.example:id/close\n区域  10,20,30,40\n可点击  true · 父级可点击  false"
        val translated = t.translate(original)
        assertTrue(translated.contains("Text  关闭"))
        assertTrue(translated.contains("Description  同意"))
        assertEquals("File: 关闭", t.translate("文件：关闭"))
        assertEquals("Will match: 应用 / 关闭", t.translate("将匹配：应用 / 关闭"))
    }
    @Test fun main_pages_and_blackbox_results_have_exact_english_labels() {
        val t = UiTranslator(catalog())
        val cases = mapOf("首页" to "Home", "广告" to "Ads", "记录" to "Records", "设置" to "Settings",
            "广告黑匣子" to "Ad blackbox", "已跳过" to "Skipped", "未成功" to "Unsuccessful",
            "待诊断" to "Needs diagnosis", "彻底疯狂" to "Crazy", "记一次漏跳" to "Report a missed ad")
        cases.forEach { (source, english) -> assertEquals(english, t.translate(source)) }
    }
}
