package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeUiRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `smoke test navigation across tabs, manual mode input, and language toggle`() {
    composeTestRule.setContent {
      val vm: MainViewModel = viewModel()
      CloudFrontScannerRoot(viewModel = vm)
    }
    composeTestRule.waitForIdle()

    // Scroll LazyColumn to Start Phase 1 button and verify
    composeTestRule
      .onNodeWithTag("scan_lazy_list")
      .performScrollToNode(hasTestTag("start_phase1_btn"))
    composeTestRule.onNodeWithTag("start_phase1_btn").assertIsDisplayed()

    // Scroll back to Mode card, switch to Manual Mode, wait for DataStore async update
    composeTestRule.onNodeWithTag("scan_lazy_list").performScrollToIndex(1)
    composeTestRule.onNodeWithTag("mode_manual_btn").performClick()
    composeTestRule.waitUntil(timeoutMillis = 4000) {
      composeTestRule.onAllNodesWithTag("manual_ip_input").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule
      .onNodeWithTag("manual_ip_input")
      .performTextInput("18.160.1.1\n2600:9000::1")
    composeTestRule.waitForIdle()

    // Navigate to Results, Logs, History, Settings
    composeTestRule.onNodeWithTag("nav_results").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("results_search_input").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_logs").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("nav_history").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("nav_settings").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lang_en_chip").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lang_fa_chip").performClick()
    composeTestRule.waitForIdle()
  }
}
