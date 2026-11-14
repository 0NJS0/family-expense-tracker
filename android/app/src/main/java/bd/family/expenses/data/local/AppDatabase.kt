package bd.family.expenses.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [TransactionEntity::class, AccountEntity::class, CategoryEntity::class, MemberEntity::class, ChangeEntity::class, ReceiptUploadEntity::class],
  version = 3,
  exportSchema = true,
  // 1 → 2 only adds the transaction_changes table; existing rows are untouched
  // (verified by MigrationTest on a device).
  // 2 → 3 only adds receipt_uploads (MigrationTest checks both steps).
  autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun transactions(): TransactionDao

  abstract fun reference(): ReferenceDao

  abstract fun changes(): ChangeDao

  abstract fun receipts(): ReceiptDao

  companion object {
    // No destructive migration fallback: unsynced entries must never be wiped
    // by an app update. Every schema change needs an explicit migration.
    fun create(context: Context): AppDatabase =
      Room.databaseBuilder(context, AppDatabase::class.java, "family-expenses.db").build()
  }
}
