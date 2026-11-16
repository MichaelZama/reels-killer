package app.bloccareels.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Lingua dell'app: "" = quella del sistema, altrimenti un tag come "it" o "en".
 * L'interfaccia la applica con AppCompat; servizio, schermata di blocco e notifica
 * usano [wrap] con il valore salvato, così funzionano anche senza un'activity aperta.
 */
object Locales {
    val SUPPORTED = listOf("", "it", "en")

    fun wrap(context: Context, tag: String): Context {
        if (tag.isEmpty()) return context
        val cfg = Configuration(context.resources.configuration)
        cfg.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(cfg)
    }

    /** Applica la lingua all'interfaccia (va chiamato dal thread principale). */
    fun applyToUi(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    /** Lingua impostata nell'interfaccia (anche dalle impostazioni di sistema per app su Android 13+). */
    fun currentUiTag(): String {
        val l = AppCompatDelegate.getApplicationLocales()
        return if (l.isEmpty) "" else l[0]?.language.orEmpty()
    }
}
