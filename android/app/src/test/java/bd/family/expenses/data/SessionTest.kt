package bd.family.expenses.data

import bd.family.expenses.data.auth.TokenStore
import bd.family.expenses.data.remote.HttpApi
import bd.family.expenses.data.remote.LoginRequiredException
import bd.family.expenses.data.remote.LoginResult
import bd.family.expenses.data.remote.NetworkException
import bd.family.expenses.data.remote.Session
import bd.family.expenses.data.remote.defaultHttpClient
import bd.family.expenses.data.remote.normaliseServerUrl
import bd.family.expenses.sync.FakeSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MemoryTokenStore(var token: String? = null) : TokenStore {
  override fun load() = token
  override fun save(refreshToken: String) { token = refreshToken }
  override fun clear() { token = null }
}

class SessionTest {
  private val server = MockWebServer()
  private val tokens = MemoryTokenStore()
  private val settings = FakeSettings(profile = null)
  private lateinit var session: Session
  private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
  private var refreshCalls = 0

  private fun json(body: String, code: Int = 200) =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

  private fun error(code: Int, err: String) = json("""{"error":{"code":"$err","message":"$err","details":[]}}""", code)

  private fun tokensJson(n: Int) = """{"token_type":"bearer","access_token":"access-$n","access_token_expires_at":"x","refresh_token":"refresh-$n","refresh_token_expires_at":"y"}"""

  @Before
  fun setUp() {
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val key = "${request.method} ${request.url.encodedPath.removePrefix("/api/v1")}"
          return routes[key]?.invoke(request) ?: error(404, "not_found")
        }
      }
    server.start()
    session = Session(HttpApi(defaultHttpClient()), tokens, settings, requireHttps = false, deviceName = "test")
    routes["POST /auth/login"] = { json(tokensJson(1)) }
    routes["GET /me"] = {
      json("""{"user":{"id":"u-amma","username":"amma","display_name":"Amma","preferred_language":"bn"},"memberships":[{"household_id":"hh-1","household_name":"Home","role":"member"}]}""")
    }
    routes["GET /households/current"] = { json("""{"id":"hh-1","name":"Home","timezone":"Asia/Dhaka","currency":"BDT","week_start":5,"created_at":"x"}""") }
    routes["POST /auth/refresh"] = {
      refreshCalls++
      Thread.sleep(50)
      json(tokensJson(refreshCalls + 1))
    }
    routes["GET /members"] = { req ->
      if (req.headers["Authorization"] == "Bearer access-1") error(401, "unauthorized")
      else json("""[{"user_id":"u-amma","display_name":"Amma","is_active":true}]""")
    }
  }

  @After fun tearDown() = server.close()

  private fun url() = server.url("/").toString()

  private suspend fun login(): LoginResult = session.login(url(), " Amma ", "pw") { true }

  @Test
  fun loginStoresProfileAndEncryptedRefreshToken() = runBlocking {
    val result = login()
    assertTrue(result is LoginResult.Success)
    assertEquals("hh-1", settings.profile.value?.householdId)
    assertEquals("Asia/Dhaka", settings.profile.value?.timezone)
    assertEquals("refresh-1", tokens.token)
    val loginRequest = server.takeRequest()
    assertTrue(loginRequest.body!!.utf8().contains("\"username\":\"amma\""))
  }

  @Test
  fun wrongPasswordIsReportedAndStoresNothing() = runBlocking {
    routes["POST /auth/login"] = { error(401, "unauthorized") }
    assertEquals(LoginResult.WrongCredentials, login())
    assertNull(tokens.token)
    assertNull(settings.profile.value)
  }

  @Test
  fun anotherPersonCannotLogInOverUnsentEntries() = runBlocking {
    val result = session.login(url(), "amma", "pw") { false }
    assertEquals(LoginResult.OtherUserHasUnsentEntries, result)
    assertNull(tokens.token)
  }

  @Test
  fun rateLimitAndUnreachableServerAreDistinguished() = runBlocking {
    routes["POST /auth/login"] = { error(429, "rate_limited") }
    assertEquals(LoginResult.TooManyAttempts, login())
    assertEquals(LoginResult.Unreachable, session.login("http://127.0.0.1:1", "amma", "pw") { true })
    assertEquals(LoginResult.InvalidServerUrl, session.login("not a url", "amma", "pw") { true })
  }

  @Test
  fun expiredAccessTokenIsRefreshedAndRetriedOnce() = runBlocking {
    login()
    val members = session.members()
    assertEquals("Amma", members.single().displayName)
    assertEquals(1, refreshCalls)
    assertEquals("refresh-2", tokens.token)
  }

  @Test
  fun parallelRequestsShareOneRefresh() = runBlocking {
    login()
    listOf(async { session.members() }, async { session.members() }, async { session.members() }).awaitAll()
    assertEquals(1, refreshCalls)
  }

  @Test
  fun rejectedRefreshRequiresLoginButNeverDeletesData() = runBlocking {
    login()
    routes["POST /auth/refresh"] = { error(401, "unauthorized") }
    try {
      session.members()
      fail("expected LoginRequiredException")
    } catch (e: LoginRequiredException) {
      // expected
    }
    assertTrue(session.loginRequired.value)
    assertNull(tokens.token)
    assertNotNull("profile is kept so the same person can sign in again", settings.profile.value)
  }

  @Test
  fun networkFailureDuringRefreshKeepsTheRefreshToken() = runBlocking {
    login()
    server.close()
    try {
      session.members()
      fail("expected NetworkException")
    } catch (e: NetworkException) {
      // expected
    }
    assertEquals("refresh-1", tokens.token)
    assertFalse(session.loginRequired.value)
  }

  @Test
  fun releaseBuildsRequireHttps() {
    assertNull(normaliseServerUrl("http://10.0.0.5:8000", requireHttps = true))
    assertEquals("https://money.home/api/v1", normaliseServerUrl("https://money.home/", requireHttps = true).toString())
    assertEquals("http://10.0.0.5:8000/api/v1", normaliseServerUrl("http://10.0.0.5:8000/api/v1", requireHttps = false).toString())
    assertNull(normaliseServerUrl("http://10.0.0.5:8000/other", requireHttps = false))
  }
}
