package bd.family.expenses.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real app (Compose UI, Room, WorkManager, OkHttp, Keystore) against
 * an in-process fake backend that behaves like the real one, including
 * idempotency on client_id.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EntryFlowTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  // Granted up front, so the system permission dialog never covers the app.
  @get:Rule val notifications: androidx.test.rule.GrantPermissionRule =
    androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

  private val server = MockWebServer()
  private val created = CopyOnWriteArrayList<String>()
  private val clientIds = CopyOnWriteArrayList<String>()
  @Volatile private var acceptTransactions = true
  private val lastAmountPoisha = CopyOnWriteArrayList<Long>()
  private val bodies = CopyOnWriteArrayList<String>()
  private val resolved = CopyOnWriteArrayList<String>()
  private val prefBodies = CopyOnWriteArrayList<String>()
  private val receiptUploads = CopyOnWriteArrayList<Pair<String, ByteArray>>()
  @Volatile private var prefsJson =
    """{"daily_reminder":false,"missing_entry_reminder":true,"reminder_time":"20:00:00","recurring_due":true,"budget_alerts":true}"""
  /** "PATCH srv-…" / "VOID srv-…" → request body, in order. */
  private val changeCalls = CopyOnWriteArrayList<Pair<String, String>>()
  @Volatile private var accountsJson = """[{"id":"acc-cash","name":"Cash","kind":"cash","is_archived":false}]"""

  private fun json(body: String, code: Int = 200) =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

  @Before
  fun setUp() {
    resetApp()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when ("${request.method} ${request.url.encodedPath.removePrefix("/api/v1")}") {
            "POST /auth/login", "POST /auth/refresh" ->
              json("""{"token_type":"bearer","access_token":"a","access_token_expires_at":"x","refresh_token":"r","refresh_token_expires_at":"y"}""")
            "GET /me" ->
              json("""{"user":{"id":"u-amma","username":"amma","display_name":"Amma","preferred_language":"bn"},"memberships":[{"household_id":"hh-1","household_name":"Test family","role":"member"}]}""")
            "GET /households/current" -> json("""{"id":"hh-1","name":"Test family","timezone":"Asia/Dhaka"}""")
            "GET /accounts" -> json(accountsJson)
            "GET /accounts/balances" ->
              json("""{"total_poisha":-150000,"accounts":[{"account_id":"acc-cash","name":"Cash","kind":"cash","is_archived":false,"balance_poisha":-150000}]}""")
            "GET /categories" ->
              json("""[{"id":"cat-groc","kind":"expense","name_en":"Groceries","name_bn":"বাজার","is_archived":false},{"id":"cat-sal","kind":"income","name_en":"Salary","name_bn":"বেতন","is_archived":false}]""")
            "GET /members" -> json("""[{"user_id":"u-amma","display_name":"Amma","is_active":true}]""")
            "GET /transactions" -> json("""{"items":[],"total":0,"limit":100,"offset":0}""")
            "GET /sync/changes" -> json("""{"items":[],"next_cursor":"c1","has_more":false}""")
            "GET /recurring/occurrences" ->
              json(if (resolved.isEmpty()) """[{"id":"occ-1","rule_name":"বাড়ি ভাড়া","type":"expense","amount_poisha":1500000,"due_date":"2026-10-01","status":"pending"}]""" else "[]")
            "POST /recurring/occurrences/occ-1/confirm" -> {
              resolved += "confirm:" + (request.body?.utf8() ?: "")
              json("""{"id":"occ-1","rule_name":"বাড়ি ভাড়া","type":"expense","amount_poisha":1500000,"due_date":"2026-10-01","status":"posted"}""")
            }
            "GET /me/notifications" -> json(prefsJson)
            "PUT /me/notifications" -> {
              val body = request.body!!.utf8()
              prefBodies += body
              prefsJson = body
              json(body)
            }
            "GET /budgets" ->
              json("""{"month":"2026-10-01","is_partial":true,"items":[{"category_id":null,"budget_poisha":1000000,"spent_poisha":1200050,"remaining_poisha":-200050,"used_basis_points":12000,"state":"over"}]}""")
            "GET /reports/summary" ->
              json("""{"date_from":"2026-10-01","date_to":"2026-10-15","is_partial":true,"income_poisha":0,"expense_poisha":${created.size * 12050L},"net_poisha":0,"transaction_count":${created.size}}""")
            "POST /transactions" -> {
              if (!acceptTransactions) return json("""{"error":{"code":"unavailable","message":"down","details":[]}}""", 503)
              val body = request.body!!.utf8()
              bodies += body
              val clientId = Regex("\"client_id\":\"([^\"]+)\"").find(body)!!.groupValues[1]
              Regex("\"amount_poisha\":(\\d+)").find(body)?.let { lastAmountPoisha += it.groupValues[1].toLong() }
              clientIds += clientId
              val first = clientId !in created
              if (first) created += clientId
              json(
                """{"id":"srv-$clientId","client_id":"$clientId","type":"expense","amount_poisha":12050,"category_id":"cat-groc","account_id":"acc-cash","destination_account_id":null,"occurred_at":"2026-10-15T12:00:00+06:00","note":null,"attributed_user_id":"u-amma","version":1,"voided_at":null}""",
                if (first) 201 else 200,
              )
            }
            else -> changeResponse(request) ?: json("""{"error":{"code":"not_found","message":"nf","details":[]}}""", 404)
          }
      }
    server.start()
  }

  /** Corrections and voids, like the real backend: version 1 → 2. */
  private fun changeResponse(request: RecordedRequest): MockResponse? {
    val path = request.url.encodedPath.removePrefix("/api/v1")
    if (request.method == "POST" && path.endsWith("/receipts")) {
      receiptUploads += path to (request.body?.toByteArray() ?: ByteArray(0))
      return json("""{"id":"rc-1","transaction_id":"x","content_type":"image/jpeg","ocr_status":"pending"}""", 201)
    }
    val m = Regex("^/transactions/srv-([^/]+)(/void)?$").find(path) ?: return null
    val clientId = m.groupValues[1]
    val voiding = m.groupValues[2].isNotEmpty()
    val body = request.body?.utf8() ?: ""
    if (request.method == "GET") return null
    changeCalls += (if (voiding) "VOID" else request.method) + " srv-$clientId" to body
    val amount = Regex("\"amount_poisha\":(\\d+)").find(body)?.groupValues?.get(1) ?: "12050"
    val voided = if (voiding) "\"2026-10-16T10:00:00+06:00\"" else "null"
    return json(
      """{"id":"srv-$clientId","client_id":"$clientId","type":"expense","amount_poisha":$amount,"category_id":"cat-groc","account_id":"acc-cash","destination_account_id":null,"occurred_at":"2026-10-15T12:00:00+06:00","note":null,"attributed_user_id":"u-amma","created_by_user_id":"u-amma","version":2,"voided_at":$voided}"""
    )
  }

  @After
  fun tearDown() {
    server.close()
    resetApp()
  }

  private fun resetApp() = runBlocking {
    val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FamilyExpensesApp).container
    container.scheduler.cancelAll()
    container.database.transactions().deleteAll()
    container.database.changes().deleteAll()
    container.database.reference().deleteAllAccounts()
    container.database.reference().deleteAllCategories()
    container.database.reference().deleteAllMembers()
    container.tokens.clear()
    container.settings.clearProfile()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("bn"))
    }
  }

  private fun waitForText(text: String, timeoutMs: Long = 15_000) =
    compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMs)

  private fun signIn() {
    waitForText("প্রবেশ করুন")
    // The address of the previous login is pre-filled on purpose; replace it.
    compose.onNodeWithText("সার্ভারের ঠিকানা").performTextClearance()
    compose.onNodeWithText("সার্ভারের ঠিকানা").performTextInput(server.url("/").toString())
    compose.onNodeWithText("ইউজারনেম").performTextInput("amma")
    compose.onNodeWithText("পাসওয়ার্ড").performTextInput("secret-pass")
    compose.onNode(hasText("প্রবেশ করুন") and hasClickAction()).performClick()
    waitForText("খরচ লিখুন")
  }

  private fun addExpense(amount: String) {
    compose.onNodeWithText("খরচ লিখুন").performClick()
    waitForText("বাজার") // categories downloaded by the first sync
    compose.onNodeWithText("টাকার পরিমাণ").performTextInput(amount)
    compose.onNodeWithText("বাজার", substring = true).performScrollTo().performClick()
    compose.onNodeWithText("সংরক্ষণ করুন").performClick()
    waitForText("সংরক্ষিত হয়েছে")
    compose.onNodeWithText("ঠিক আছে").performClick()
  }

  @Test
  fun banglaByDefault_entrySavedAndSent() {
    signIn()
    addExpense("১২০.৫০")
    waitForText("সব হিসাব পাঠানো হয়েছে", 30_000)
    assertEquals(1, created.size)
    compose.onNodeWithText("আগের হিসাব").performClick()
    waitForText("পাঠানো হয়েছে")
    compose.onNodeWithText("−৳১২০.৫০").assertExists()
  }

  @Test
  fun offlineEntryWaitsAndIsSentOnceWhenTheServerReturns() {
    signIn()
    acceptTransactions = false
    addExpense("120.50")
    waitForText("১টি হিসাব পাঠানোর অপেক্ষায়", 20_000)
    assertTrue(created.isEmpty())

    acceptTransactions = true
    compose.onNodeWithText("সেটিংস").performClick()
    compose.onNodeWithText("এখনই পাঠান").performClick()
    waitForText("সব হিসাব পাঠানো হয়েছে", 30_000)
    assertEquals(1, created.size)
    // Every retry reused the same client id, so the server holds exactly one entry.
    assertEquals(1, clientIds.distinct().size)
  }

  @Test
  fun quickAmountSavesExactlyThatAmount() {
    signIn()
    compose.onNodeWithText("খরচ লিখুন").performClick()
    waitForText("বাজার")
    compose.onNodeWithText("৳১০০").performClick()
    compose.onNodeWithText("বাজার", substring = true).performScrollTo().performClick()
    compose.onNodeWithText("সংরক্ষণ করুন").performClick()
    waitForText("সংরক্ষিত হয়েছে")
    compose.onNodeWithText("ঠিক আছে").performClick()
    waitForText("সব হিসাব পাঠানো হয়েছে", 30_000)
    assertTrue(lastAmountPoisha.contains(10_000L))
  }

  @Test
  fun missingCategoryIsExplainedNextToSave() {
    signIn()
    compose.onNodeWithText("খরচ লিখুন").performClick()
    waitForText("বাজার")
    compose.onNodeWithText("টাকার পরিমাণ").performTextInput("120")
    compose.onNodeWithText("সংরক্ষণ করুন").performClick()
    waitForText("উপরের লাল লেখাগুলো দেখুন")
    waitForText("কিসের জন্য তা বেছে নিন")
  }

  @Test
  fun invalidAmountIsExplainedAndNothingIsSaved() {
    signIn()
    compose.onNodeWithText("খরচ লিখুন").performClick()
    waitForText("বাজার")
    compose.onNodeWithText("টাকার পরিমাণ").performTextInput("12,50")
    compose.onNodeWithText("বাজার", substring = true).performScrollTo().performClick()
    compose.onNodeWithText("সংরক্ষণ করুন").performClick()
    waitForText("১২০ বা ১২০.৫০ এভাবে পরিমাণ লিখুন")
    runBlocking {
      val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FamilyExpensesApp).container
      assertEquals(0, container.database.transactions().unsyncedCount())
    }
  }

  @Test
  fun transferBetweenTwoAccountsIsSavedAndSentWithoutCategory() {
    accountsJson =
      """[{"id":"acc-cash","name":"Cash","kind":"cash","is_archived":false},{"id":"acc-bkash","name":"bKash","kind":"mobile_wallet","is_archived":false}]"""
    signIn()
    compose.onNodeWithText("স্থানান্তর", substring = true).performScrollTo().performClick()
    waitForText("bKash")
    compose.onNodeWithText("টাকার পরিমাণ").performTextInput("৫০০")
    // Nothing chosen yet: both account sections explain what is missing.
    compose.onNodeWithText("সংরক্ষণ করুন").performClick()
    waitForText("টাকা কোথা থেকে নিলেন তা বেছে নিন")
    compose.onAllNodes(hasText("Cash") and hasClickAction())[0].performScrollTo().performClick()
    // Cash is now the source, so it is no longer offered as the destination.
    assertEquals(1, compose.onAllNodes(hasText("Cash", substring = true) and hasClickAction()).fetchSemanticsNodes().size)
    // bKash appears under both "From" and "To"; the second one is the destination.
    compose.onAllNodes(hasText("bKash") and hasClickAction())[1].performScrollTo().performClick()
    compose.onNodeWithText("সংরক্ষণ করুন").performClick()
    waitForText("সংরক্ষিত হয়েছে")
    compose.onNodeWithText("ঠিক আছে").performClick()
    waitForText("সব হিসাব পাঠানো হয়েছে", 30_000)
    val body = bodies.single()
    assertTrue(body, body.contains("\"type\":\"transfer\""))
    assertTrue(body, body.contains("\"amount_poisha\":50000"))
    assertTrue(body, body.contains("\"category_id\":null"))
    assertTrue(body, body.contains("\"account_id\":\"acc-cash\""))
    assertTrue(body, body.contains("\"destination_account_id\":\"acc-bkash\""))
  }

  @Test
  fun balancesShowTheServerFiguresOffline() {
    signIn()
    waitForText("সব হিসাব পাঠানো হয়েছে", 30_000)
    compose.onNodeWithText("ব্যালেন্স").performScrollTo().performClick()
    // Exactly the server's (negative) figure, for the account and the total.
    waitForText("হিসাবের ব্যালেন্স")
    assertEquals(2, compose.onAllNodes(hasText("−৳১,৫০০.০০")).fetchSemanticsNodes().size)
  }

  private fun openOnlyEntry() {
    waitForText("সব হিসাব পাঠানো হয়েছে", 30_000)
    compose.onNodeWithText("আগের হিসাব").performClick()
    waitForText("পাঠানো হয়েছে")
    compose.onNodeWithText("−৳১২০.৫০").performClick()
    waitForText("হিসাবের বিবরণ")
  }

  @Test
  fun correctingAnAmountSendsOnlyTheChangeWithTheVersion() {
    signIn()
    addExpense("120.50")
    openOnlyEntry()
    compose.onNodeWithText("এই হিসাবটি সংশোধন করুন").performScrollTo().performClick()
    waitForText("হিসাব সংশোধন")
    compose.onNode(hasText("টাকার পরিমাণ") and hasSetTextAction()).performTextClearance()
    compose.onNode(hasText("টাকার পরিমাণ") and hasSetTextAction()).performTextInput("150")
    compose.onNodeWithText("সংরক্ষণ করুন").performClick()
    waitForText("সংশোধন সংরক্ষিত হয়েছে")
    compose.onNodeWithText("ঠিক আছে").performClick()
    // Back on the entry: the correction is sent and the server's answer is shown.
    compose.waitUntil(30_000) { changeCalls.isNotEmpty() }
    val (call, body) = changeCalls.single()
    assertTrue(call, call.startsWith("PATCH srv-"))
    assertEquals("""{"version":1,"amount_poisha":15000}""", body)
    waitForText("−৳১৫০.০০")
    waitForText("✓ পাঠানো হয়েছে", 30_000)
  }

  @Test
  fun cancellingAnEntrySendsAVoidWithTheReasonAndHidesIt() {
    signIn()
    addExpense("120.50")
    openOnlyEntry()
    compose.onNodeWithText("এই হিসাবটি বাতিল করুন").performScrollTo().performClick()
    waitForText("হিসাবটি বাতিল করবেন?")
    compose.onNodeWithText("দুবার লেখা হয়েছে").performClick()
    // The dialog's confirm button has the same words as the screen's button.
    compose.onAllNodes(hasText("এই হিসাবটি বাতিল করুন") and hasClickAction()).fetchSemanticsNodes().let { assertEquals(2, it.size) }
    compose.onAllNodes(hasText("এই হিসাবটি বাতিল করুন") and hasClickAction())[1].performClick()
    compose.waitUntil(30_000) { changeCalls.isNotEmpty() }
    val (call, body) = changeCalls.single()
    assertTrue(call, call.startsWith("VOID srv-"))
    assertEquals("""{"version":1,"reason":"দুবার লেখা হয়েছে"}""", body)
    // Once confirmed by the server, a voided entry leaves the history list.
    compose.onNodeWithContentDescription("ফিরে যান").performClick()
    compose.waitUntil(30_000) { compose.onAllNodes(hasText("−৳১২০.৫০")).fetchSemanticsNodes().isEmpty() }
  }

  @Test
  fun homeShowsTheBudgetInWordsWithExactServerFigures() {
    signIn()
    waitForText("এই মাসের বাজেট", 30_000)
    waitForText("⚠ বাজেট ছাড়িয়েছে")
    waitForText("৳২,০০০.৫০ বেশি")
    compose.onNodeWithText("সব বাজেট দেখুন").performScrollTo().performClick()
    waitForText("পুরো পরিবার")
  }

  @Test
  fun aDueRentIsRecordedOnlyAfterSayingYes() {
    signIn()
    waitForText("দেওয়ার সময় হয়েছে", 30_000)
    compose.onNodeWithText("দেওয়া হয়েছে").performScrollTo().performClick()
    // Nothing is recorded until the person confirms exactly what will be written.
    waitForText("বাড়ি ভাড়া ৳১৫,০০০.০০ দেওয়া হয়েছে বলে লিখবেন?")
    assertTrue(resolved.isEmpty())
    compose.onNodeWithText("হ্যাঁ, দেওয়া হয়েছে").performClick()
    compose.waitUntil(15_000) { resolved.isNotEmpty() }
    assertEquals(listOf("confirm:{}"), resolved.toList())
    compose.waitUntil(15_000) { compose.onAllNodes(hasText("দেওয়ার সময় হয়েছে")).fetchSemanticsNodes().isEmpty() }
  }

  @Test
  fun reminderChoicesAreSavedOnTheServer() {
    signIn()
    waitForText("সব হিসাব পাঠানো হয়েছে", 30_000)
    compose.onNodeWithText("সেটিংস").performScrollTo().performClick()
    compose.onNodeWithText("প্রতিদিন সন্ধ্যায় মনে করিয়ে দিন").performScrollTo().performClick()
    compose.waitUntil(15_000) { prefBodies.isNotEmpty() }
    assertTrue(prefBodies.last(), prefBodies.last().contains("\"daily_reminder\":true"))
    compose.onNodeWithText("২১:০০", substring = true).performScrollTo().performClick()
    compose.waitUntil(15_000) { prefBodies.size >= 2 }
    assertTrue(prefBodies.last(), prefBodies.last().contains("\"reminder_time\":\"21:00:00\""))
  }

  @Test
  fun aReceiptPhotoIsShrunkOnThePhoneAndSentAfterItsEntry() {
    signIn()
    addExpense("120.50")
    waitForText("সব হিসাব পাঠানো হয়েছে", 30_000)
    val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FamilyExpensesApp).container
    val clientId = clientIds.first()
    // A big "camera photo" (the camera app itself cannot be driven in a test).
    val photo = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "camera-test.jpg")
    val bitmap = android.graphics.Bitmap.createBitmap(4000, 3000, android.graphics.Bitmap.Config.ARGB_8888)
    photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
    runBlocking { container.receipts.addPhoto(clientId, photo) }
    compose.waitUntil(30_000) { receiptUploads.isNotEmpty() }
    val (path, bytes) = receiptUploads.single()
    assertEquals("/transactions/srv-$clientId/receipts", path)
    assertTrue("a JPEG", bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
    val sent = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    assertTrue("shrunk to at most 2000 px", maxOf(sent.width, sent.height) <= 2000)
    assertTrue(!photo.exists())
    // Shown on the entry as sent.
    compose.onNodeWithText("আগের হিসাব").performClick()
    compose.onNodeWithText("−৳১২০.৫০").performClick()
    waitForText("পাঠানো হয়েছে")
    waitForText("রসিদের ছবি তুলুন")
  }

  /**
   * Regression test for a rare crash when the signed-in profile disappears
   * (logout, session end) while a screen is showing: screens used to return
   * early after a composable call, which corrupted Compose's slot table.
   */
  @Test
  fun signingOutWhileOnAnyScreenNeverCrashes() {
    val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FamilyExpensesApp).container
    val screens = listOf("আগের হিসাব", "ব্যালেন্স", "সেটিংস", "খরচ লিখুন", "স্থানান্তর")
    repeat(2) {
      for (screen in screens) {
        signIn()
        compose.onNodeWithText(screen, substring = true).performScrollTo().performClick()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.settings.clearProfile() }
        waitForText("প্রবেশ করুন")
      }
    }
  }
}
