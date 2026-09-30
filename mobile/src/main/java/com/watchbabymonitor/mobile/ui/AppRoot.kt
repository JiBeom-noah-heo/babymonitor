package com.watchbabymonitor.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Screen { HOME, SETTINGS, HISTORY }

/** 폰 화면 전환: 홈 / 설정 / 기록. 세 화면이라 내비게이션 라이브러리 없이 상태 하나로. */
@Composable
fun AppRoot(modifier: Modifier = Modifier, viewModel: HomeViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    when (screen) {
        Screen.HOME -> HomeScreen(
            modifier = modifier,
            viewModel = viewModel,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenHistory = { screen = Screen.HISTORY },
        )
        Screen.SETTINGS -> SettingsScreen(viewModel, onBack = { screen = Screen.HOME }, modifier = modifier)
        Screen.HISTORY -> HistoryScreen(viewModel, onBack = { screen = Screen.HOME }, modifier = modifier)
    }
}
