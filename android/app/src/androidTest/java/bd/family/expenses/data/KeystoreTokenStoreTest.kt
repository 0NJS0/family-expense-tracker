package bd.family.expenses.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import bd.family.expenses.data.auth.KeystoreTokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun roundTripsWithoutStoringPlaintext() {
    val store = KeystoreTokenStore(context)
    store.save("super-secret-refresh-token")
    assertEquals("super-secret-refresh-token", store.load())
    val raw = context.getSharedPreferences("auth", Context.MODE_PRIVATE).all.values.joinToString()
    assertFalse(raw.contains("super-secret-refresh-token"))
    // A second instance (e.g. after a restart) can still decrypt it.
    assertEquals("super-secret-refresh-token", KeystoreTokenStore(context).load())
    store.clear()
    assertNull(store.load())
  }

  @Test
  fun corruptedDataIsDiscardedInsteadOfCrashing() {
    context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().putString("refresh_token", "bm90LXZhbGlk").commit()
    assertNull(KeystoreTokenStore(context).load())
  }
}
