package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.domain.model.AppLanguage
import com.example.ui.MainViewModel
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.i18n.stringsFor
import com.example.ui.navigation.HistoryRoute
import com.example.ui.navigation.LogsRoute
import com.example.ui.navigation.QrScannerRoute
import com.example.ui.navigation.ResultsRoute
import com.example.ui.navigation.ScanRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.QrScannerScreen
import com.example.ui.screens.ResultsScreen
import com.example.ui.screens.ScanScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CloudFrontScannerTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val vm: MainViewModel = viewModel()
      CloudFrontScannerRoot(viewModel = vm)
    }
  }
}

private data class TopTabItem(
  val route: Any,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudFrontScannerRoot(viewModel: MainViewModel) {
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val bannerMsg by viewModel.userBannerMsg.collectAsStateWithLifecycle()
  val strings = stringsFor(settings.language)
  val layoutDir = if (strings.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

  val context = LocalContext.current
  val notifLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) {
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(bannerMsg) {
    val msg = bannerMsg
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearBannerMsg()
    }
  }

  CompositionLocalProvider(
    LocalAppStrings provides strings,
    LocalLayoutDirection provides layoutDir,
  ) {
    CloudFrontScannerTheme(themeMode = settings.themeMode) {
      val navController = rememberNavController()
      val backStackEntry by navController.currentBackStackEntryAsState()
      val currentDest = backStackEntry?.destination
      val isQrScreen = currentDest?.hasRoute<QrScannerRoute>() == true

      val tabs =
        listOf(
          TopTabItem(
            ScanRoute,
            strings.tabScan,
            Icons.Filled.Radar,
            Icons.Outlined.Radar,
            "nav_scan",
          ),
          TopTabItem(
            ResultsRoute,
            strings.tabResults,
            Icons.Filled.Analytics,
            Icons.Outlined.Analytics,
            "nav_results",
          ),
          TopTabItem(
            LogsRoute,
            strings.tabLogs,
            Icons.Filled.Terminal,
            Icons.Outlined.Terminal,
            "nav_logs",
          ),
          TopTabItem(
            HistoryRoute,
            strings.tabHistory,
            Icons.Filled.History,
            Icons.Outlined.History,
            "nav_history",
          ),
          TopTabItem(
            SettingsRoute,
            strings.tabSettings,
            Icons.Filled.Settings,
            Icons.Outlined.Settings,
            "nav_settings",
          ),
        )

      fun navigateToTab(route: Any) {
        navController.navigate(route) {
          popUpTo(navController.graph.findStartDestination().id) { saveState = true }
          launchSingleTop = true
          restoreState = true
        }
      }

      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
          snackbarHost = { SnackbarHost(snackbarHostState) },
          topBar = {
            if (!isQrScreen) {
              TopAppBar(
                title = { Text(strings.appTitle) },
                actions = {
                  TextButton(
                    onClick = {
                      val next =
                        if (settings.language == AppLanguage.FA) AppLanguage.EN
                        else AppLanguage.FA
                      viewModel.setLanguage(next)
                    },
                    modifier = Modifier.testTag("toggle_lang_btn"),
                  ) {
                    Text(
                      text = if (settings.language == AppLanguage.FA) "EN" else "فا",
                      style = MaterialTheme.typography.labelLarge,
                    )
                  }
                },
              )
            }
          },
          bottomBar = {
            if (!useRail && !isQrScreen) {
              NavigationBar {
                tabs.forEach { tab ->
                  val selected = currentDest?.hasRoute(tab.route::class) == true
                  NavigationBarItem(
                    selected = selected,
                    onClick = { navigateToTab(tab.route) },
                    icon = {
                      Icon(
                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                      )
                    },
                    label = { Text(tab.label, maxLines = 1) },
                    modifier = Modifier.testTag(tab.testTag),
                  )
                }
              }
            }
          },
        ) { innerPadding ->
          Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (useRail && !isQrScreen) {
              NavigationRail {
                tabs.forEach { tab ->
                  val selected = currentDest?.hasRoute(tab.route::class) == true
                  NavigationRailItem(
                    selected = selected,
                    onClick = { navigateToTab(tab.route) },
                    icon = {
                      Icon(
                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                      )
                    },
                    label = { Text(tab.label) },
                    modifier = Modifier.testTag(tab.testTag),
                  )
                }
              }
            }

            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.TopCenter,
            ) {
              Box(modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                NavHost(
                  navController = navController,
                  startDestination = ScanRoute,
                ) {
                  composable<ScanRoute> {
                    ScanScreen(
                      viewModel = viewModel,
                      onOpenQrScanner = { navController.navigate(QrScannerRoute) },
                      onNavigateToResults = { navigateToTab(ResultsRoute) },
                    )
                  }
                  composable<ResultsRoute> { ResultsScreen(viewModel = viewModel) }
                  composable<LogsRoute> { LogsScreen(viewModel = viewModel) }
                  composable<HistoryRoute> {
                    HistoryScreen(
                      viewModel = viewModel,
                      onOpenResults = { navigateToTab(ResultsRoute) },
                    )
                  }
                  composable<SettingsRoute> { SettingsScreen(viewModel = viewModel) }
                  composable<QrScannerRoute> {
                    QrScannerScreen(
                      onQrDecoded = { decoded ->
                        viewModel.updateVlessUri(decoded)
                        navController.popBackStack()
                      },
                      onClose = { navController.popBackStack() },
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
