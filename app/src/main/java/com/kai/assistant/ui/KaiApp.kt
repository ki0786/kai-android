package com.kai.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kai.assistant.data.SettingsRepository
import com.kai.assistant.ui.books.BooksScreen
import com.kai.assistant.ui.business.BusinessInfoScreen
import com.kai.assistant.ui.business.EnquiryInboxScreen
import com.kai.assistant.ui.chat.TextChatScreen
import com.kai.assistant.ui.home.HomeScreen
import com.kai.assistant.ui.navigation.KaiDestination
import com.kai.assistant.ui.settings.SettingsScreen
import com.kai.assistant.ui.voice.VoiceSessionScreen

@Composable
fun KaiApp(
    settingsRepository: SettingsRepository,
    openEnquiryInbox: Boolean = false,
) {
    val navController = rememberNavController()

    LaunchedEffect(openEnquiryInbox) {
        if (openEnquiryInbox) {
            navController.navigate(KaiDestination.EnquiryInbox.route) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = KaiDestination.Home.route,
    ) {
        composable(KaiDestination.Home.route) {
            HomeScreen(
                onStartVoice = { navController.navigate(KaiDestination.Voice.route) },
                onOpenChat = { navController.navigate(KaiDestination.Chat.route) },
                onOpenSettings = { navController.navigate(KaiDestination.Settings.route) },
                onOpenEnquiryInbox = { navController.navigate(KaiDestination.EnquiryInbox.route) },
                onOpenBooks = { navController.navigate(KaiDestination.Books.route) },
            )
        }
        composable(KaiDestination.Voice.route) {
            VoiceSessionScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = {
                    navController.navigate(KaiDestination.Chat.route) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(KaiDestination.Chat.route) {
            TextChatScreen(
                onBack = { navController.popBackStack() },
                onOpenVoice = {
                    navController.navigate(KaiDestination.Voice.route) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(KaiDestination.Settings.route) {
            SettingsScreen(
                settingsRepository = settingsRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(KaiDestination.EnquiryInbox.route) {
            EnquiryInboxScreen(
                onBack = { navController.popBackStack() },
                onOpenBusinessInfo = {
                    navController.navigate(KaiDestination.BusinessInfo.route)
                },
            )
        }
        composable(KaiDestination.BusinessInfo.route) {
            BusinessInfoScreen(onBack = { navController.popBackStack() })
        }
        composable(KaiDestination.Books.route) {
            BooksScreen(onBack = { navController.popBackStack() })
        }
    }
}
