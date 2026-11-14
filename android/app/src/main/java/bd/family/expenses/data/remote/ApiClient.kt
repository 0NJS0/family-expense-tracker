package bd.family.expenses.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The server answered with an error envelope. */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)

/** The server could not be reached (offline, VPN down, timeout…). Always retryable. */
class NetworkException(cause: IOException) : Exception(cause.message, cause)

/** The session is gone (refresh rejected); a person must log in again. */
class LoginRequiredException : Exception("login required")

val json = Json {
  ignoreUnknownKeys = true
  explicitNulls = true
  encodeDefaults = true
}

/** "http://host:8000" (or with a trailing /api/v1) → base for /api/v1 calls, or null if invalid. */
fun normaliseServerUrl(input: String, requireHttps: Boolean): HttpUrl? {
  val url = input.trim().trimEnd('/').removeSuffix("/api/v1").toHttpUrlOrNull() ?: return null
  if (requireHttps && !url.isHttps) return null
  if (url.encodedPath != "/" || url.query != null) return null
  return url.newBuilder().addPathSegments("api/v1").build()
}

fun defaultHttpClient(): OkHttpClient =
  OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    // Retries are decided by the sync engine, never silently by the transport.
    .retryOnConnectionFailure(false)
    .build()

/** Low-level HTTP: JSON in and out, error envelopes become [ApiException]. */
class HttpApi(private val http: OkHttpClient) {
  private val jsonType = "application/json".toMediaType()
  private val octetType = "application/octet-stream".toMediaType()

  suspend fun <T> call(
    base: HttpUrl,
    method: String,
    path: String,
    response: KSerializer<T>?,
    body: String? = null,
    accessToken: String? = null,
    householdId: String? = null,
    query: Map<String, String> = emptyMap(),
    /** A raw (non-JSON) body, e.g. a receipt photo. */
    bytes: ByteArray? = null,
  ): HttpResult<T> =
    withContext(Dispatchers.IO) {
      val url =
        base.newBuilder().apply {
          addPathSegments(path.trimStart('/'))
          query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
      val request =
        Request.Builder()
          .url(url)
          .header("Accept", "application/json")
          .apply {
            accessToken?.let { header("Authorization", "Bearer $it") }
            householdId?.let { header("X-Household-ID", it) }
          }
          .method(method, bytes?.toRequestBody(octetType) ?: body?.toRequestBody(jsonType))
          .build()
      try {
        http.newCall(request).execute().use { parse(it, response) }
      } catch (e: IOException) {
        throw NetworkException(e)
      }
    }

  private fun <T> parse(res: Response, serializer: KSerializer<T>?): HttpResult<T> {
    val text = res.body.string()
    if (res.code == 401) return HttpResult.Unauthorized
    if (!res.isSuccessful) {
      val error = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), text).error }.getOrNull()
      throw ApiException(res.code, error?.code ?: "http_error", error?.message ?: "HTTP ${res.code}")
    }
    @Suppress("UNCHECKED_CAST")
    val value = if (serializer == null) Unit as T else json.decodeFromString(serializer, text)
    return HttpResult.Ok(value, res.code)
  }
}

sealed interface HttpResult<out T> {
  data class Ok<T>(val value: T, val status: Int) : HttpResult<T>

  data object Unauthorized : HttpResult<Nothing>
}
