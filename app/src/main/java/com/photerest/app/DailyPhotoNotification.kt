package com.photerest.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

/** Public Android notification APIs only; SystemUI owns the header and card decoration. */
internal object DailyPhotoNotification {
    fun message(context: Context, test: Boolean): String = context.getString(
        if (test) R.string.daily_photo_test_message else R.string.daily_photo_message)

    fun build(context: Context, bitmap: Bitmap, intent: PendingIntent, preview: Boolean, test: Boolean, group: String?): Notification {
        val body = message(context, test)
        val public = NotificationCompat.Builder(context, DailyRecommendations.CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("每日精选").setContentText("解锁查看推荐照片").setContentIntent(intent).build()
        fun builder() = NotificationCompat.Builder(context, DailyRecommendations.CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Photerest").setContentText(body).setContentIntent(intent)
            .setAutoCancel(true).setOnlyAlertOnce(true).setGroup(group)
            .setVisibility(if (preview) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)

        val image = roundedPreview(bounded(bitmap))
        val thumb = roundedSquare(bitmap, context.resources.getDimensionPixelSize(R.dimen.notification_photo_edge).coerceIn(36, 192))
        return try {
            val small = RemoteViews(context.packageName, R.layout.notification_daily_small).apply {
                setImageViewBitmap(R.id.notification_photo, thumb)
                setTextViewText(R.id.notification_message, body)
                // Keep the row inside Android 12+'s 48dp custom-content budget.
                setInt(R.id.notification_message, "setMaxLines", if (context.resources.configuration.fontScale > 1.2f) 1 else 2)
            }
            val large = RemoteViews(context.packageName, R.layout.notification_daily_large).apply {
                setImageViewBitmap(R.id.notification_big_photo, image)
                setTextViewText(R.id.notification_message, body)
            }
            // Validate resources/actions before asking another process to inflate them.
            small.apply(context, FrameLayout(context))
            large.apply(context, FrameLayout(context))
            builder().setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small).setCustomBigContentView(large).setCustomHeadsUpContentView(small).build()
        } catch (e: RuntimeException) {
            Log.w("DailyPhotos", "Custom notification resources unavailable; using system picture template", e)
            builder().setLargeIcon(thumb).setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as Bitmap?)).build()
        }
    }

    private fun bounded(bitmap: Bitmap): Bitmap {
        val scale = minOf(1f, 768f / maxOf(bitmap.width, bitmap.height))
        return if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }
    /** Round the photo itself, so FIT_CENTER letterboxing cannot leave square photo corners. */
    private fun roundedPreview(bitmap: Bitmap): Bitmap {
        val radius = minOf(bitmap.width, bitmap.height) / 12f
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRoundRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), radius, radius,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        }
    }
    private fun roundedSquare(bitmap: Bitmap, edge: Int): Bitmap {
        val scale = edge.toFloat() / minOf(bitmap.width, bitmap.height)
        val transform = Matrix().apply {
            setScale(scale, scale)
            postTranslate((edge - bitmap.width * scale) / 2, (edge - bitmap.height * scale) / 2)
        }
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(transform) }
        return Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRoundRect(RectF(0f, 0f, edge.toFloat(), edge.toFloat()), edge / 12f, edge / 12f,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        }
    }
}
