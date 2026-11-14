package bd.family.expenses.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import bd.family.expenses.data.local.ReceiptDao
import bd.family.expenses.data.local.ReceiptUploadEntity
import bd.family.expenses.data.local.SyncState
import bd.family.expenses.data.settings.SettingsStore
import java.io.File
import java.time.Clock
import java.util.UUID

/**
 * Receipt photos: kept in the app's private storage and queued, like entries,
 * so taking a photo works offline. The sync uploads them once their entry is
 * on the server, then deletes the local copy.
 */
class ReceiptRepository(
  private val receipts: ReceiptDao,
  private val settings: SettingsStore,
  private val dir: File,
  private val requestSync: () -> Unit,
  private val clock: Clock = Clock.systemUTC(),
  private val newId: () -> String = { UUID.randomUUID().toString() },
  /** Writes a smaller JPEG of [source] to [target] (the server re-encodes again). */
  private val shrink: (source: File, target: File) -> Unit = ::shrinkPhoto,
) {
  suspend fun addPhoto(clientId: String, photo: File) {
    val profile = checkNotNull(settings.profile.value) { "not signed in" }
    val id = newId()
    dir.mkdirs()
    val target = File(dir, "$id.jpg")
    shrink(photo, target)
    photo.delete()
    receipts.insert(
      ReceiptUploadEntity(
        id = id,
        clientId = clientId,
        householdId = profile.householdId,
        createdByUserId = profile.userId,
        filePath = target.absolutePath,
        createdLocallyAtMs = clock.millis(),
      )
    )
    requestSync()
  }

  /** Drop a waiting or refused photo (it never reached the server). */
  suspend fun discard(item: ReceiptUploadEntity) {
    check(item.state != SyncState.SYNCED) { "already on the server" }
    receipts.delete(item.id)
    File(item.filePath).delete()
  }
}

/** At most 2000 px on the long side, upright, JPEG quality 85: small enough for a slow connection. */
fun shrinkPhoto(source: File, target: File) {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(source.path, bounds)
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
  val bitmap =
    BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
      ?: error("not an image")
  val degrees =
    when (ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
      ExifInterface.ORIENTATION_ROTATE_90 -> 90f
      ExifInterface.ORIENTATION_ROTATE_180 -> 180f
      ExifInterface.ORIENTATION_ROTATE_270 -> 270f
      else -> 0f
    }
  val upright =
    if (degrees == 0f) bitmap
    else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
  target.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 85, it) }
}
