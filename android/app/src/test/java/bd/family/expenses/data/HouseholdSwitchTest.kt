package bd.family.expenses.data

import bd.family.expenses.data.remote.HttpApi
import bd.family.expenses.data.remote.Session
import bd.family.expenses.data.remote.SummaryDto
import bd.family.expenses.data.remote.defaultHttpClient
import bd.family.expenses.sync.FakeSettings
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HouseholdSwitchTest {
  private val server = MockWebServer()
  private val settings = FakeSettings(profile = null)
  private lateinit var session: Session

  private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

  @Before
  fun setUp() {
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val path = request.url.encodedPath.removePrefix("/api/v1")
          return when ("${request.method} $path") {
            "POST /auth/login" -> ok("""{"access_token":"a","refresh_token":"r"}""")
            "GET /me" ->
              ok("""{"user":{"id":"u1","username":"amma","display_name":"Amma","preferred_language":"bn"},"memberships":[{"household_id":"hh-home","household_name":"Home","role":"member"},{"household_id":"hh-shop","household_name":"Shop","role":"admin"}]}""")
            // The household comes from the X-Household-ID header, like the real server.
            "GET /households/current" ->
              when (request.headers["X-Household-ID"]) {
                "hh-shop" -> ok("""{"id":"hh-shop","name":"Shop","timezone":"Asia/Dhaka"}""")
                else -> ok("""{"id":"hh-home","name":"Home","timezone":"Asia/Dhaka"}""")
              }
            else -> MockResponse.Builder().code(404).build()
          }
        }
      }
    server.start()
    session = Session(HttpApi(defaultHttpClient()), MemoryTokenStore(), settings, requireHttps = false, deviceName = "t")
    runBlocking { session.login(server.url("/").toString(), "amma", "pw") { true } }
  }

  @After fun tearDown() = server.close()

  @Test
  fun switchingChangesHouseholdRoleAndForgetsTheOldFigures() = runBlocking {
    settings.cachedSummary = SummaryDto("2026-10-01", "2026-10-16", true, 0, 999, 0, 1)
    settings.syncCursor = "old-feed-position"
    assertTrue(session.switchHousehold("hh-shop") { 0 })
    val p = settings.profile.value!!
    assertEquals("hh-shop" to "Shop", p.householdId to p.householdName)
    assertEquals("admin", p.role)
    assertNull(settings.cachedSummary)
    assertNull(settings.syncCursor) // the new household's feed starts from the beginning
  }

  @Test
  fun switchingIsRefusedWhileAnythingIsUnsent() = runBlocking {
    assertFalse(session.switchHousehold("hh-shop") { 2 })
    assertEquals("hh-home", settings.profile.value?.householdId)
  }
}
