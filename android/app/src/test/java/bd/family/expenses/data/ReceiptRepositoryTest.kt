package bd.family.expenses.data

import bd.family.expenses.data.local.SyncState
import bd.family.expenses.sync.FakeReceiptDao
import bd.family.expenses.sync.FakeSettings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptRepositoryTest {
  private val dao = FakeReceiptDao()
  private val dir = Files.createTempDirectory("receipts").toFile()
  private var syncs = 0
  private val repo =
    ReceiptRepository(dao, FakeSettings(), dir, { syncs++ }, newId = { "r1" }, shrink = { src, dst -> src.copyTo(dst) })

  @Test
  fun aPhotoIsKeptPrivatelyAndQueued() = runTest {
    val photo = File.createTempFile("camera", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    repo.addPhoto("tx-1", photo)
    val item = dao.items.getValue("r1")
    assertEquals(SyncState.PENDING, item.state)
    assertEquals("tx-1", item.clientId)
    assertEquals(File(dir, "r1.jpg").absolutePath, item.filePath)
    assertTrue(File(item.filePath).exists())
    assertFalse(photo.exists()) // the camera's temporary file is not left behind
    assertEquals(1, syncs)
  }

  @Test
  fun aWaitingPhotoCanBeRemoved() = runTest {
    repo.addPhoto("tx-1", File.createTempFile("camera", ".jpg"))
    val item = dao.items.getValue("r1")
    repo.discard(item)
    assertTrue(dao.items.isEmpty())
    assertFalse(File(item.filePath).exists())
  }
}
