package com.photerest.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Cross-version layout suite: does not depend on MediaStore's Android 10+ insertion API. */
class NotificationLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun intent() = PendingIntent.getActivity(context, 919, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun bitmap(w: Int = 480, h: Int = 720): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it); canvas.drawColor(Color.rgb(38, 65, 100))
        canvas.drawCircle(w * .7f, h * .25f, minOf(w, h) * .18f, Paint(3).apply { color = Color.rgb(255, 205, 135) })
    }
    private fun layout(view: View, widthDp: Int, maxHeightDp: Int, ctx: Context) {
        val density = ctx.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((maxHeightDp * density).toInt(), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    @Test fun narrowAndLargeTextKeepPhotoLeftAndInsideHeightBudget() {
        val image = bitmap()
        for (font in listOf(.85f, 1f, 1.5f, 2f)) for (width in listOf(240, 360)) {
            val config = Configuration(context.resources.configuration).apply { fontScale = font }
            val ctx = context.createConfigurationContext(config)
            val notification = DailyPhotoNotification.build(ctx, image, intent(), true, true, null)
            assertNotNull("Custom layout unexpectedly fell back", notification.contentView)
            assertNull(notification.getLargeIcon())
            val view = notification.contentView.apply(ctx, FrameLayout(ctx))
            layout(view, width, 48, ctx)
            val photo = view.findViewById<ImageView>(R.id.notification_photo)
            val text = view.findViewById<TextView>(R.id.notification_message)
            val density = ctx.resources.displayMetrics.density
            assertEquals(ctx.resources.getDimensionPixelSize(R.dimen.notification_photo_edge), photo.width)
            assertTrue(photo.right <= text.left)
            assertTrue(view.measuredHeight / density <= 48.5f)
            assertTrue(text.right <= view.measuredWidth)
            assertEquals(if (font > 1.2f) 1 else 2, text.maxLines)
            assertTrue(text.layout.lineCount <= text.maxLines)
        }
        image.recycle()
    }
    @Test fun expandedPhotosPreserveAspectAcrossShapesAndThemes() {
        for ((w, h) in listOf(720 to 1080, 1200 to 500, 180 to 2400)) for (dark in listOf(false, true)) {
            val image = bitmap(w, h)
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val ctx = context.createConfigurationContext(config)
            val notification = DailyPhotoNotification.build(ctx, image, intent(), true, false, null)
            val view = notification.bigContentView.apply(ctx, FrameLayout(ctx))
            layout(view, 260, 252, ctx)
            val photo = view.findViewById<ImageView>(R.id.notification_big_photo)
            val preview = (photo.drawable as android.graphics.drawable.BitmapDrawable).bitmap
            listOf(0 to 0, preview.width - 1 to 0, 0 to preview.height - 1, preview.width - 1 to preview.height - 1).forEach { (x, y) ->
                assertEquals("Photo corners must be transparent", 0, Color.alpha(preview.getPixel(x, y)))
            }
            assertEquals(255, Color.alpha(preview.getPixel(preview.width / 2, preview.height / 2)))
            assertEquals("Original bitmap must remain untouched", 255, Color.alpha(image.getPixel(0, 0)))
            assertEquals(ImageView.ScaleType.FIT_CENTER, photo.scaleType)
            val matrix = FloatArray(9).also { photo.imageMatrix.getValues(it) }
            assertEquals(matrix[Matrix.MSCALE_X], matrix[Matrix.MSCALE_Y], .001f)
            assertTrue(view.measuredHeight / ctx.resources.displayMetrics.density <= 252)
            assertEquals(context.getString(R.string.daily_photo_message), view.findViewById<TextView>(R.id.notification_message).text.toString())
            image.recycle()
        }
    }
    @Test fun privatePublicVersionHasNoImageOrRawFilename() {
        val image = bitmap()
        val notification = DailyPhotoNotification.build(context, image, intent(), false, false, null)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertNull(notification.publicVersion.contentView)
        assertNull(notification.publicVersion.bigContentView)
        assertNull(notification.publicVersion.getLargeIcon())
        assertEquals("解锁查看推荐照片", notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        image.recycle()
    }
    @Test fun systemShadeActuallyDisplaysCustomPhotoLayout() {
        val automation = instrumentation.uiAutomation
        val service = automation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = service
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val manager = context.getSystemService(NotificationManager::class.java)
        DailyRecommendations.get(context).createChannel()
        val image = bitmap()
        manager.notify("layout-compat", 919, DailyPhotoNotification.build(context, image, intent(), true, false, null))
        try {
            shell("input keyevent 224"); shell("wm dismiss-keyguard"); shell("cmd statusbar expand-notifications")
            fun find(node: android.view.accessibility.AccessibilityNodeInfo, predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
                if (predicate(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child, predicate)?.let { return it } }
                return null
            }
            fun roots() = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            fun body() = roots().firstNotNullOfOrNull { find(it) { n -> n.text?.toString() == context.getString(R.string.daily_photo_message) } }
            fun expand(expanded: Boolean) {
                var parent = body()
                while (parent != null) {
                    val button = find(parent) { it.isClickable && it.contentDescription?.toString()?.startsWith(if (expanded) "Expand" else "Collapse") == true }
                    if (button != null) { button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); return }
                    if (parent.viewIdResourceName?.endsWith("expandableNotificationRow") == true) return
                    parent = parent.parent
                }
            }
            val deadline = System.currentTimeMillis() + 8_000
            var found = false
            while (!found && System.currentTimeMillis() < deadline) {
                found = body() != null
                if (!found) Thread.sleep(200)
            }
            val folder = File(context.getExternalFilesDir(null), "notification-layout-evidence").apply { mkdirs() }
            fun capture(name: String) {
                Thread.sleep(750) // SystemUI animations run outside Compose/test-idle synchronization.
                val screen = instrumentation.uiAutomation.takeScreenshot()
                File(folder, "api-${Build.VERSION.SDK_INT}-$name.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screen.recycle()
            }
            if (!found) {
                fun texts(node: android.view.accessibility.AccessibilityNodeInfo): String = buildString {
                    append(node.text); append(' '); append(node.contentDescription); append('\n')
                    for (i in 0 until node.childCount) node.getChild(i)?.let { append(texts(it)) }
                }
                File(folder, "api-${Build.VERSION.SDK_INT}-ui.txt").writeText(automation.windows.mapNotNull { it.root }.joinToString("\n") { texts(it) })
            }
            assertTrue("SystemUI did not display the custom photo notification", found)
            expand(true); capture("expanded")
            expand(false); capture("collapsed")
            val thumbnail = roots().firstNotNullOfOrNull { find(it) { n -> n.viewIdResourceName == "${context.packageName}:id/notification_photo" } }
            assertNotNull("Collapsed left photo missing from actual SystemUI", thumbnail)
            val photoRect = android.graphics.Rect().also { thumbnail!!.getBoundsInScreen(it) }
            val textRect = android.graphics.Rect().also { body()!!.getBoundsInScreen(it) }
            assertTrue("Photo must be to the left of the message", photoRect.right <= textRect.left)
            val expected = context.resources.getDimensionPixelSize(R.dimen.notification_photo_edge)
            assertTrue("Photo was clipped: $photoRect, expected edge $expected", photoRect.height() >= expected - 2)
        } finally { manager.cancel("layout-compat", 919); shell("cmd statusbar collapse"); image.recycle() }
    }
}
