package com.freemax.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.freemax.app.ui.auth.AuthScreen
import com.freemax.app.ui.auth.PasteTokenScreen
import com.freemax.app.ui.auth.SiteLogin
import com.freemax.app.ui.chat.ChatScreen
import com.freemax.app.ui.chat.HeaderTarget
import com.freemax.app.ui.chats.ChatsScreen
import com.freemax.app.ui.profile.ContactProfileScreen
import com.freemax.app.ui.profile.GroupInfoScreen
import com.freemax.app.ui.profile.OwnProfileScreen
import com.freemax.app.ui.search.SearchScreen
import com.freemax.app.ui.settings.AccountsScreen
import com.freemax.app.ui.settings.SettingsScreen

object Routes {
    const val AUTH = "auth"
    const val WEBVIEW_LOGIN = "webview_login"
    const val CHATS = "chats"
    const val CHAT = "chat/{chatId}"
    const val SETTINGS = "settings"
    const val ACCOUNTS = "accounts"
    const val SEARCH = "search"
    const val CONTACT = "contact/{contactId}"
    const val GROUP_INFO = "groupinfo/{chatId}"
    const val MY_PROFILE = "myprofile"
    const val CHAT_SEARCH = "chatsearch/{chatId}"

    fun chat(chatId: Long) = "chat/$chatId"
    fun contact(contactId: Long) = "contact/$contactId"
    fun groupInfo(chatId: Long) = "groupinfo/$chatId"
    fun chatSearch(chatId: Long) = "chatsearch/$chatId"
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = Routes.AUTH
) {
    // Gecko-экран входа есть только в debug (src/debug, ServiceLoader).
    // В release siteLogin == null, и вход идёт через PasteTokenScreen.
    val siteLogin = SiteLogin.provider
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.AUTH) {
            AuthScreen(
                onNavigateToWebView = siteLogin?.let {
                    {
                        navController.navigate(Routes.WEBVIEW_LOGIN) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    }
                },
                onNavigateToPasteToken = {
                    navController.navigate("paste_token")
                },
                onLoginSuccess = {
                    navController.navigate(Routes.CHATS) {
                        popUpTo(Routes.AUTH) { inclusive = true }
                    }
                }
            )
        }

        // Fallback: paste __oneme_auth from a real browser.
        // In-app site login stays the primary flow; this is the escape hatch.
        composable("paste_token") {
            PasteTokenScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.CHATS) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        if (siteLogin != null) {
            composable(Routes.WEBVIEW_LOGIN) {
                siteLogin.Screen(
                    onAuthSaved = {
                        navController.navigate(Routes.CHATS) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(Routes.CHATS) {
            ChatsScreen(
                onChatClick = { chatId ->
                    navController.navigate(Routes.chat(chatId))
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                },
                onSearchClick = {
                    navController.navigate(Routes.SEARCH)
                },
                onNewChatClick = {
                    navController.navigate(Routes.SEARCH)
                },
                onProfileClick = {
                    navController.navigate(Routes.MY_PROFILE)
                },
                onAccountsClick = {
                    navController.navigate(Routes.ACCOUNTS)
                }
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = {
                    navController.popBackStack()
                },
                onOpenChat = { chatId ->
                    navController.navigate(Routes.chat(chatId))
                },
                onOpenContact = { contactId ->
                    navController.navigate(Routes.contact(contactId))
                }
            )
        }

        composable(
            route = Routes.CONTACT,
            arguments = listOf(navArgument("contactId") { type = NavType.LongType })
        ) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getLong("contactId") ?: 0L
            ContactProfileScreen(
                contactId = contactId,
                onBack = {
                    navController.popBackStack()
                },
                onOpenDialog = { chatId ->
                    navController.navigate(Routes.chat(chatId)) {
                        popUpTo(Routes.CHATS)
                    }
                }
            )
        }

        composable(
            route = "groupinfo/{chatId}",
            arguments = listOf(
                navArgument("chatId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getLong("chatId") ?: 0L
            GroupInfoScreen(
                chatId = chatId,
                onBack = {
                    navController.popBackStack()
                },
                onMemberClick = { contactId, isSelf ->
                    if (isSelf) {
                        navController.navigate(Routes.MY_PROFILE)
                    } else {
                        navController.navigate(Routes.contact(contactId))
                    }
                }
            )
        }

        composable(Routes.MY_PROFILE) {
            OwnProfileScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    navController.popBackStack()
                },
                onLoggedOut = {
                    navController.navigate(Routes.AUTH) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onProfileClick = {
                    navController.navigate(Routes.MY_PROFILE)
                },
                onAccountsClick = {
                    navController.navigate(Routes.ACCOUNTS)
                }
            )
        }

        composable(Routes.ACCOUNTS) {
            AccountsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("chatId") { type = NavType.LongType })
        ) {
            ChatScreen(
                onBack = {
                    navController.popBackStack()
                },
                onProfileClick = { target, title ->
                    when (target) {
                        is HeaderTarget.Contact -> navController.navigate(Routes.contact(target.contactId))
                        is HeaderTarget.Group -> navController.navigate(Routes.groupInfo(target.chatId))
                        is HeaderTarget.Self -> navController.navigate(Routes.MY_PROFILE)
                    }
                },
                onOpenContact = { contactId ->
                    navController.navigate(Routes.contact(contactId))
                },
                onSearchClick = { chatId ->
                    navController.navigate(Routes.chatSearch(chatId))
                }
            )
        }

        composable(
            route = Routes.CHAT_SEARCH,
            arguments = listOf(navArgument("chatId") { type = NavType.LongType })
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getLong("chatId") ?: 0L
            com.freemax.app.ui.search.ChatSearchScreen(
                chatId = chatId,
                onBack = { navController.popBackStack() },
                onOpenChat = { id ->
                    navController.navigate(Routes.chat(id)) {
                        popUpTo(Routes.CHAT_SEARCH) { inclusive = true }
                    }
                }
            )
        }
    }
}
