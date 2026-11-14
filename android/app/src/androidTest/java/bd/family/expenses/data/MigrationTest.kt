package bd.family.expenses.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import bd.family.expenses.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * App updates must never lose entries that are still waiting to be sent: every
 * schema version is migrated, never recreated (docs/android.md).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
  @get:Rule
  val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

  @Test
  fun version1To2KeepsEveryEntryAndAddsTheChangeOutbox() {
    helper.createDatabase(DB, 1).use { db ->
      db.execSQL(
        "INSERT INTO transactions (client_id, household_id, server_id, type, amount_poisha, category_id, account_id, " +
          "destination_account_id, occurred_at, occurred_at_epoch_ms, note, attributed_user_id, created_by_user_id, " +
          "sync_state, attempts, last_error, server_version, voided, created_locally_at_ms) VALUES " +
          "('unsent-1', 'hh', NULL, 'expense', 12050, 'c', 'a', NULL, '2026-10-15T12:00:00+06:00', 1, 'ঔষধ', NULL, 'u1', 'PENDING', 3, 'network', NULL, 0, 1), " +
          "('sent-1', 'hh', 'srv', 'income', 500000, 'c2', 'a', NULL, '2026-10-14T12:00:00+06:00', 2, NULL, NULL, NULL, 'SYNCED', 0, NULL, 4, 0, 0)"
      )
    }
    helper.runMigrationsAndValidate(DB, 2, true).use { db ->
      db.query("SELECT client_id, amount_poisha, note, sync_state, attempts, server_version FROM transactions ORDER BY client_id").use { c ->
        assertEquals(2, c.count)
        c.moveToFirst()
        assertEquals("sent-1", c.getString(0))
        assertEquals(500000L, c.getLong(1))
        assertEquals(4, c.getInt(5))
        c.moveToNext()
        assertEquals("unsent-1", c.getString(0))
        assertEquals(12050L, c.getLong(1))
        assertEquals("ঔষধ", c.getString(2))
        assertEquals("PENDING", c.getString(3))
        assertEquals(3, c.getInt(4))
      }
      db.query("SELECT COUNT(*) FROM transaction_changes").use { c ->
        c.moveToFirst()
        assertEquals(0, c.getInt(0))
      }
    }
  }

  @Test
  fun version2To3KeepsEverythingAndAddsTheReceiptOutbox() {
    helper.createDatabase(DB3, 2).use { db ->
      db.execSQL(
        "INSERT INTO transactions (client_id, household_id, server_id, type, amount_poisha, category_id, account_id, " +
          "destination_account_id, occurred_at, occurred_at_epoch_ms, note, attributed_user_id, created_by_user_id, " +
          "sync_state, attempts, last_error, server_version, voided, created_locally_at_ms) VALUES " +
          "('unsent-1', 'hh', NULL, 'expense', 12050, 'c', 'a', NULL, '2026-10-15T12:00:00+06:00', 1, NULL, NULL, 'u1', 'PENDING', 0, NULL, NULL, 0, 1)"
      )
      db.execSQL(
        "INSERT INTO transaction_changes (client_id, household_id, created_by_user_id, kind, base_version, note_changed, state, attempts, created_locally_at_ms) " +
          "VALUES ('unsent-1', 'hh', 'u1', 'void', 1, 0, 'PENDING', 0, 2)"
      )
    }
    helper.runMigrationsAndValidate(DB3, 3, true).use { db ->
      db.query("SELECT COUNT(*) FROM transactions").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
      db.query("SELECT kind FROM transaction_changes").use { c -> c.moveToFirst(); assertEquals("void", c.getString(0)) }
      db.query("SELECT COUNT(*) FROM receipt_uploads").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }
  }

  private companion object {
    const val DB = "migration-test.db"
    const val DB3 = "migration-test-3.db"
  }
}
