package app.bloccareels.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * App language: "" = the system one, otherwise a tag such as "it" or "en".
 * The UI applies it through AppCompat; the service, block screen and notification
 * use [wrap] with the saved value, so they work without an open activity.
 */
object Locales {
    val SUPPORTED = listOf("", "it", "en")

    fun wrap(context: Context, tag: String): Context {
        if (tag.isEmpty()) return context
        val cfg = Configuration(context.resources.configuration)
        cfg.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(cfg)
    }

    /** Applies the language to the UI (must be called from the main thread). */
    fun applyToUi(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    /** Language set on the UI (also from the per-app system settings on Android 13+). */
    fun currentUiTag(): String {
        val l = AppCompatDelegate.getApplicationLocales()
        return if (l.isEmpty) "" else l[0]?.language.orEmpty()
    }
}
