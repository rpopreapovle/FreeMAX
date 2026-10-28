package com.freemax.app.ui.auth

import androidx.compose.runtime.Composable

/** Debug-реализация входа через сайт: полноценный GeckoView-экран. */
class GeckoLoginProvider : SiteLoginProvider {
    @Composable
    override fun Screen(onAuthSaved: () -> Unit, onBack: () -> Unit) {
        GeckoLoginScreen(onAuthSaved = onAuthSaved, onBack = onBack)
    }
}
