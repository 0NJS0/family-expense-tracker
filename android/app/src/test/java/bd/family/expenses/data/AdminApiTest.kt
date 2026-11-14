package bd.family.expenses.data

import bd.family.expenses.data.remote.AdminApi
import bd.family.expenses.data.remote.ApiException
import bd.family.expenses.data.remote.HttpApi
import bd.family.expenses.data.remote.Session
import bd.family.expenses.data.remote.defaultHttpClient
import bd.family.expenses.data.remote.json
import bd.family.expenses.sync.FakeSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AdminApiTest {
  private val server = MockWebServer()
  private val requests = mutableListOf<Pair<String, String>>()
  private lateinit var admin: AdminApi

  private fun ok(body: String, code: Int = 200) =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

  private val tx =
    """{"id":"t1","type":"expense","amount_poisha":12050,"category_id":"c1","account_id":"a1","occurred_at":"2026-10-15T10:00:00+06:00","note":"rice","attributed_user_id":"u1","created_by_user_id":"u1","version":2,"voided_at":null}"""

  @Before
  fun setUp() {
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val key = "${request.method} ${request.url.encodedPath.removePrefix("/api/v1")}"
          requests += key to (request.body?.utf8() ?: "")
          return when (key) {
            "POST /auth/login" -> ok("""{"access_token":"a","refresh_token":"r"}""")
            "GET /me" -> ok("""{"user":{"id":"u1","username":"admin","display_name":"Admin","preferred_language":"bn"},"memberships":[{"household_id":"hh","household_name":"Home","role":"admin"}]}""")
            "GET /households/current" -> ok("""{"id":"hh","name":"Home","timezone":"Asia/Dhaka"}""")
            "PATCH /transactions/t1", "POST /transactions/t1/void" -> ok(tx)
            "PATCH /transactions/stale" ->
              ok("""{"error":{"code":"conflict","message":"Transaction was changed by someone else","details":[]}}""", 409)
            "POST /accounts" -> ok("""{"id":"a9","name":"bKash","kind":"mobile_wallet","is_archived":false}""", 201)
            "PATCH /members/u2" -> ok("""{"user_id":"u2","username":"amma","display_name":"Amma","role":"viewer","is_active":true}""")
            else -> ok("""{"error":{"code":"not_found","message":"nf","details":[]}}""", 404)
          }
        }
      }
    server.start()
    val settings = FakeSettings(profile = null)
    val session = Session(HttpApi(defaultHttpClient()), MemoryTokenStore(), settings, requireHttps = false, deviceName = "t")
    runBlocking { session.login(server.url("/").toString(), "admin", "pw") { true } }
    assertEquals(true, settings.profile.value?.isAdmin)
    admin = AdminApi(session)
  }

  @After fun tearDown() = server.close()

  private fun bodyOf(key: String): JsonObject = json.parseToJsonElement(requests.last { it.first == key }.second).jsonObject

  @Test
  fun editSendsOnlyChangedFieldsWithTheShownVersion() = runBlocking {
    admin.updateTransaction("t1", version = 2, amountPoisha = 15075)
    assertEquals("""{"version":2,"amount_poisha":15075}""", bodyOf("PATCH /transactions/t1").toString())
    admin.updateTransaction("t1", version = 2, clearNote = true)
    assertEquals("""{"version":2,"note":null}""", bodyOf("PATCH /transactions/t1").toString())
  }

  @Test
  fun staleEditSurfacesTheConflict() = runBlocking {
    try {
      admin.updateTransaction("stale", version = 1, amountPoisha = 1)
      fail("expected a conflict")
    } catch (e: ApiException) {
      assertEquals(409, e.status)
    }
  }

  @Test
  fun voidSendsVersionAndReason() = runBlocking {
    admin.voidTransaction("t1", version = 2, reason = "Entered twice")
    assertEquals("""{"version":2,"reason":"Entered twice"}""", bodyOf("POST /transactions/t1/void").toString())
  }

  @Test
  fun createAccountSendsExactSignedOpeningBalance() = runBlocking {
    admin.createAccount("bKash", "mobile_wallet", "bKash", -125075L, "2026-10-15")
    val body = bodyOf("POST /accounts")
    assertEquals("""{"amount_poisha":-125075,"as_of_date":"2026-10-15"}""", body["opening_balance"].toString())
    admin.createAccount("Cash box", "cash", null, null, null)
    assertTrue("no opening balance when none was given", "opening_balance" !in bodyOf("POST /accounts"))
  }

  @Test
  fun memberChangesSendOnlyWhatChanged() = runBlocking {
    admin.updateMember("u2", role = "viewer")
    assertEquals("""{"role":"viewer"}""", bodyOf("PATCH /members/u2").toString())
    admin.updateMember("u2", active = false)
    assertEquals("""{"is_active":false}""", bodyOf("PATCH /members/u2").toString())
  }
}
