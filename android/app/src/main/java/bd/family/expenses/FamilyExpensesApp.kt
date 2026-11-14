package bd.family.expenses

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.WorkManager
import bd.family.expenses.data.ChangeRepository
import bd.family.expenses.data.EntryRepository
import bd.family.expenses.data.ReceiptRepository
import bd.family.expenses.data.auth.KeystoreTokenStore
import bd.family.expenses.data.local.AppDatabase
import bd.family.expenses.data.remote.AdminApi
import bd.family.expenses.data.remote.HttpApi
import bd.family.expenses.data.remote.Session
import bd.family.expenses.data.remote.defaultHttpClient
import bd.family.expenses.data.settings.PrefsSettingsStore
import bd.family.expenses.notify.Notifier
import bd.family.expenses.notify.ReminderScheduler
import bd.family.expenses.sync.SyncEngine
import bd.family.expenses.sync.SyncScheduler

class FamilyExpensesApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
    if (container.settings.profile.value != null) {
      container.scheduler.schedulePeriodic()
      container.scheduler.syncNow()
      container.settings.profile.value?.let { container.reminders.schedule(container.settings.notificationPrefs, it.timezone) }
    }
  }
}

/** Manual dependency wiring; small enough not to need a DI framework. */
class AppContainer(context: Context) {
  val database = AppDatabase.create(context)
  val settings = PrefsSettingsStore(context)
  val tokens = KeystoreTokenStore(context)
  val session =
    Session(
      http = HttpApi(defaultHttpClient()),
      tokens = tokens,
      settings = settings,
      // Release builds only talk to the server over HTTPS (docs/android.md).
      requireHttps = !BuildConfig.DEBUG,
      deviceName = "Android ${Build.VERSION.RELEASE} (${Build.MODEL})".take(100),
    )
  /** Online-only management endpoints, used by admins' Manage screens. */
  val admin = AdminApi(session)
  val scheduler = SyncScheduler(WorkManager.getInstance(context))
  val notifier = Notifier(context)
  val reminders = ReminderScheduler(WorkManager.getInstance(context))
  val syncEngine =
    SyncEngine(
      database.transactions(),
      database.changes(),
      database.reference(),
      session,
      settings,
      notifier = notifier,
      onPrefs = { prefs, timezone -> reminders.schedule(prefs, timezone) },
      receipts = database.receipts(),
    )
  val receipts =
    ReceiptRepository(
      database.receipts(),
      settings,
      java.io.File(context.filesDir, "receipts"),
      requestSync = scheduler::syncNow,
    )
  val entries = EntryRepository(database.transactions(), settings, requestSync = scheduler::syncNow)
  val changes = ChangeRepository(database.transactions(), database.changes(), settings, requestSync = scheduler::syncNow)
}
