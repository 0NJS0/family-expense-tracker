package bd.family.expenses.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import bd.family.expenses.FamilyExpensesApp
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Manage area, driven through the real app against a fake backend. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AdminFlowTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val server = MockWebServer()
  private val calls = CopyOnWriteArrayList<Pair<String, String>>()
  @Volatile private var role = "admin"

  private fun json(body: String, code: Int = 200) =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

  private val tx =
    """{"id":"t1","client_id":"c1","type":"expense","amount_poisha":12050,"category_id":"cat-groc","account_id":"acc-cash","destination_account_id":null,"occurred_at":"2026-10-10T12:00:00+06:00","note":"rice","attributed_user_id":"u-1","created_by_user_id":"u-1","version":1,"voided_at":null}"""

  @Before
  fun setUp() {
    reset()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val key = "${request.method} ${request.url.encodedPath.removePrefix("/api/v1")}"
          calls += key to (request.body?.utf8() ?: "")
          return when (key) {
            "POST /auth/login", "POST /auth/refresh" -> json("""{"access_token":"a","refresh_token":"r"}""")
            "GET /me" ->
              json("""{"user":{"id":"u-1","username":"boro","display_name":"Boro","preferred_language":"bn"},"memberships":[{"household_id":"hh-1","household_name":"Test family","role":"$role"}]}""")
            "GET /households/current" -> json("""{"id":"hh-1","name":"Test family","timezone":"Asia/Dhaka"}""")
            "GET /accounts" -> json("""[{"id":"acc-cash","name":"নগদ","kind":"cash","is_archived":false}]""")
            "GET /accounts/balances" ->
              json("""{"total_poisha":-12050,"accounts":[{"account_id":"acc-cash","name":"নগদ","kind":"cash","is_archived":false,"balance_poisha":-12050}]}""")
            "GET /categories" ->
              json("""[{"id":"cat-groc","kind":"expense","name_en":"Groceries","name_bn":"বাজার","is_archived":false,"is_default":true}]""")
            "GET /members" -> json("""[{"user_id":"u-1","username":"boro","display_name":"Boro","role":"$role","is_active":true}]""")
            "GET /transactions" -> json("""{"items":[$tx],"total":1,"limit":25,"offset":0}""")
            "GET /sync/changes" -> json("""{"items":[],"next_cursor":"c1","has_more":false}""")
            "GET /budgets" -> json("""{"month":"2026-10-01","is_partial":true,"items":[]}""")
            "GET /recurring/occurrences" -> json("[]")
            "GET /ai-reports" ->
              json("""[{"id":"r1","period_start":"2026-09-01","language":"bn","status":"done","text":"সেপ্টেম্বরে খরচ 19500.00 টাকা।"},{"id":"r2","period_start":"2026-08-01","language":"bn","status":"rejected","text":null}]""")
            "GET /reports/summary" ->
              json(
                """{"date_from":"2026-10-01","date_to":"2026-10-15","is_partial":true,"income_poisha":5000000,"expense_poisha":123456,"net_poisha":4876544,"transfer_poisha":0,"transaction_count":2,
                "expense_by_category":[{"category_id":"cat-groc","kind":"expense","name_en":"Groceries","name_bn":"বাজার","total_poisha":123456,"transaction_count":1}],
                "by_member":[{"user_id":"u-1","display_name":"Boro","income_poisha":5000000,"expense_poisha":123456,"transaction_count":2}],
                "comparison":{"date_from":"2026-09-01","date_to":"2026-09-15","transaction_count":0,"income_change_poisha":0,"expense_change_poisha":0,"expense_change_basis_points":null}}"""
              )
            "POST /accounts" -> json("""{"id":"acc-new","name":"বিকাশ","kind":"mobile_wallet","is_archived":false}""", 201)
            "PATCH /transactions/t1" -> json(tx.replace("\"amount_poisha\":12050", "\"amount_poisha\":15000").replace("\"version\":1", "\"version\":2"))
            else -> json("""{"error":{"code":"not_found","message":"nf","details":[]}}""", 404)
          }
        }
      }
    server.start()
  }

  @After
  fun tearDown() {
    server.close()
    reset()
  }

  private fun reset() = runBlocking {
    val c = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FamilyExpensesApp).container
    c.scheduler.cancelAll()
    c.database.transactions().deleteAll()
    c.database.changes().deleteAll()
    c.database.reference().deleteAllAccounts()
    c.database.reference().deleteAllCategories()
    c.database.reference().deleteAllMembers()
    c.tokens.clear()
    c.settings.clearProfile()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("bn"))
    }
  }

  private fun waitForText(text: String, timeoutMs: Long = 15_000) =
    compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMs)

  private fun signIn() {
    waitForText("প্রবেশ করুন")
    compose.onNodeWithText("সার্ভারের ঠিকানা").performTextClearance()
    compose.onNodeWithText("সার্ভারের ঠিকানা").performTextInput(server.url("/").toString())
    compose.onNodeWithText("ইউজারনেম").performTextInput("boro")
    compose.onNodeWithText("পাসওয়ার্ড").performTextInput("secret-pass")
    compose.onNode(hasText("প্রবেশ করুন") and hasClickAction()).performClick()
    waitForText("খরচ লিখুন")
  }

  private fun openManage() {
    waitForText("পরিচালনা")
    compose.onNodeWithText("পরিচালনা", substring = true).performScrollTo().performClick()
    waitForText("রিপোর্ট")
  }

  @Test
  fun adminSeesReportsFromTheServer() {
    signIn()
    openManage()
    compose.onNodeWithText("রিপোর্ট", substring = true).performClick()
    waitForText("৳১,২৩৪.৫৬") // expenses, exactly as the server reported
    waitForText("৳৫০,০০০.০০") // income
    waitForText("Boro")
  }

  @Test
  fun adminAddsAnAccountWithAnExactStartingBalance() {
    signIn()
    openManage()
    compose.onNodeWithText("হিসাবসমূহ", substring = true).performClick()
    waitForText("হিসাব যোগ করুন")
    compose.onNode(hasText("হিসাবের নাম") and hasSetTextAction()).performScrollTo().performTextInput("বিকাশ")
    compose.onNodeWithText("মোবাইল ওয়ালেট").performScrollTo().performClick()
    compose.onNodeWithText("শুরুর ব্যালেন্স (ইচ্ছা হলে)").performScrollTo().performTextInput("-500")
    compose.onNodeWithText("তৈরি করুন").performScrollTo().performClick()
    compose.waitUntil(10_000) { calls.any { it.first == "POST /accounts" } }
    val body = calls.last { it.first == "POST /accounts" }.second
    assertTrue(body, body.contains("\"kind\":\"mobile_wallet\""))
    assertTrue(body, body.contains("\"amount_poisha\":-50000"))
  }

  @Test
  fun adminCorrectsAnAmountSendingOnlyTheChangeAndVersion() {
    signIn()
    openManage()
    compose.onNodeWithText("সব লেনদেন", substring = true).performClick()
    waitForText("rice")
    compose.onNodeWithText("rice").performClick()
    compose.onNodeWithText("সংশোধন").performClick()
    compose.onNode(hasText("টাকার পরিমাণ") and hasSetTextAction()).performTextClearance()
    compose.onNode(hasText("টাকার পরিমাণ") and hasSetTextAction()).performTextInput("150")
    compose.onNodeWithText("সংরক্ষণ করুন").performClick()
    waitForText("সংরক্ষিত হয়েছে")
    val body = calls.last { it.first == "PATCH /transactions/t1" }.second
    assertTrue(body, body == """{"version":1,"amount_poisha":15000}""")
  }

  @Test
  fun membersDoNotSeeManage() {
    role = "member"
    signIn()
    waitForText("সব হিসাব পাঠানো হয়েছে", 20_000)
    assertTrue(compose.onAllNodes(hasText("পরিচালনা", substring = true)).fetchSemanticsNodes().isEmpty())
  }

  @Test
  fun adminReadsOnlyCheckedAiSummaries() {
    signIn()
    openManage()
    compose.onNodeWithText("AI সারসংক্ষেপ", substring = true).performScrollTo().performClick()
    waitForText("সেপ্টেম্বরে খরচ 19500.00 টাকা।")
    waitForText("প্রতিটি সংখ্যা হিসাবের সাথে মিলিয়ে দেখা হয়েছে")
  }
}
