package bd.family.expenses.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end against a real backend. Skipped unless run with, e.g.:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=bd.family.expenses.ui.RealBackendTest \
 *     -Pandroid.testInstrumentationRunnerArguments.fetServer=http://10.0.2.2:8000 \
 *     -Pandroid.testInstrumentationRunnerArguments.fetUser=amma \
 *     -Pandroid.testInstrumentationRunnerArguments.fetPassword=…
 * Use a throwaway test database, never production. The app is left signed in
 * so its screens can be inspected afterwards.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RealBackendTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val args = InstrumentationRegistry.getArguments()

  private fun waitForText(text: String, timeoutMs: Long = 30_000) =
    compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMs)

  @Test
  fun signInAddExpenseAndSync() {
    val server = args.getString("fetServer")
    assumeTrue("no fetServer argument: skipped", server != null)
    waitForText("প্রবেশ করুন")
    compose.onNodeWithText("সার্ভারের ঠিকানা").performTextClearance()
    compose.onNodeWithText("সার্ভারের ঠিকানা").performTextInput(server!!)
    compose.onNodeWithText("ইউজারনেম").performTextInput(args.getString("fetUser")!!)
    compose.onNodeWithText("পাসওয়ার্ড").performTextInput(args.getString("fetPassword")!!)
    compose.onNode(hasText("প্রবেশ করুন") and hasClickAction()).performClick()
    waitForText("খরচ লিখুন")

    compose.onNodeWithText("খরচ লিখুন").performClick()
    waitForText("বাজার")
    compose.onNodeWithText("টাকার পরিমাণ").performTextInput("১,২৫০.৭৫")
    compose.onNodeWithText("বাজার", substring = true).performScrollTo().performClick()
    // New households have three accounts (নগদ, বিকাশ, ব্যাংক), so one must be chosen.
    compose.onNode(hasText("নগদ") and hasClickAction()).performScrollTo().performClick()
    compose.onNodeWithText("নোট (ইচ্ছা হলে)").performScrollTo().performTextInput("e2e test entry")
    compose.onNodeWithText("সংরক্ষণ করুন").performClick()
    waitForText("সংরক্ষিত হয়েছে")
    compose.onNodeWithText("ঠিক আছে").performClick()
    waitForText("সব হিসাব পাঠানো হয়েছে", 60_000)
  }
}
