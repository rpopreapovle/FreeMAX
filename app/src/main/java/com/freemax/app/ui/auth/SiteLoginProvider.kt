package com.freemax.app.ui.auth

import androidx.compose.runtime.Composable

/**
 * Экран входа через сайт MAX (GeckoView). Реализация есть только в debug
 * source-set (src/debug) и подгружается через ServiceLoader.
 *
 * В release реализаций нет: GeckoView не входит в сборку (см. build.gradle.kts,
 * зависимость только debugImplementation), а вход выполняется через
 * PasteTokenScreen.
 */
interface SiteLoginProvider {
    @Composable
    fun Screen(onAuthSaved: () -> Unit, onBack: () -> Unit)
}

object SiteLogin {
    /** null, если в сборке нет Gecko-экрана (release). */
    val provider: SiteLoginProvider? by lazy {
        try {
            java.util.ServiceLoader.load(SiteLoginProvider::class.java).firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }
}
