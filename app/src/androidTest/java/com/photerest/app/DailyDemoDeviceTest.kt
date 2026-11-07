package com.photerest.app

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Creates only named synthetic demo media in the dedicated emulator for manual UI capture. */
@PermissionScenario
class DailyDemoDeviceTest {
    @Test fun seedDemo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scan = Gallery.scan(context)
        val demoAlbums = scan.first.filter { it.name == "PhoterestDailyDemo" }.map { it.id }.toSet()
        scan.second.filter { it.albumId in demoAlbums && it.name.startsWith("落日与远山") }.forEach {
            context.contentResolver.delete(android.net.Uri.parse(it.uri), null, null)
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "落日与远山.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhoterestDailyDemo")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 900f, 1200f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, 1200f, intArrayOf(0xff243962.toInt(), 0xffe5a07b.toInt()), null, Shader.TileMode.CLAMP) })
        canvas.drawCircle(620f, 350f, 125f, Paint(3).apply { color = 0xffffdeb0.toInt() })
        canvas.drawPath(Path().apply { moveTo(0f, 900f); lineTo(320f, 460f); lineTo(650f, 950f); lineTo(780f, 720f); lineTo(900f, 900f); lineTo(900f, 1200f); lineTo(0f, 1200f); close() }, Paint(3).apply { color = 0xff193847.toInt() })
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }; bitmap.recycle()
        val photos = Gallery.scan(context).second
        val photo = photos.first { android.content.ContentUris.parseId(android.net.Uri.parse(it.uri)) == android.content.ContentUris.parseId(uri) }
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("dark", true).commit()
        val repository = DailyRecommendations.get(context)
        runBlocking { repository.update { it.copy(enabled = true, source = DailySource.ALBUMS, albums = setOf(photo.albumId), times = listOf(540, 900, 1200), lockscreenPreview = true) } }
        assertTrue(repository.notificationsAllowed())
        println("Demo prepared. Send a notification from the production settings UI.")
    }
    @Test fun verifyRestartedSettingsAndSchedule() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = DailyRecommendations.get(context)
        assertTrue(repository.state.value.enabled)
        assertEquals(listOf(540, 900, 1200), repository.state.value.times)
        assertEquals(DailySource.ALBUMS, repository.state.value.source)
        assertTrue(repository.state.value.lockscreenPreview)
        runBlocking { repository.reconcile() }
        assertNotNull(DailyAlarms(context).pending())
        assertTrue(context.getSharedPreferences("daily_recommendations", Context.MODE_PRIVATE).getLong("alarm_at", 0) > System.currentTimeMillis())
    }
}
