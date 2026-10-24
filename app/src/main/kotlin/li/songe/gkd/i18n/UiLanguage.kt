package li.songe.gkd.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import kotlinx.serialization.json.Json
import li.songe.gkd.BuildConfig
import li.songe.gkd.app
import java.util.Locale

object UiLanguage {
    val english: Boolean get() = BuildConfig.BYPASS_UI_LANGUAGE == "en"
    private val translator by lazy {
        val source = app.assets.open("ui_en.json").bufferedReader().use { it.readText() }
        UiTranslator(Json.decodeFromString<Map<String, String>>(source))
    }
    fun text(value: String): String = if (english) translator.translate(value) else value

    fun context(base: Context): Context {
        if (!english) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.ENGLISH))
        return base.createConfigurationContext(config)
    }
}
