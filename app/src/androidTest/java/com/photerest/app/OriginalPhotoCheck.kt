package com.photerest.app

import android.app.Instrumentation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun Instrumentation.checkOriginalOpened(photo: Photo, click: () -> Unit) {
    fun shell(command: String) = uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    fun tap(text: String): Boolean {
        var target = uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: return false
        while (!target.isClickable) target = target.parent ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    val originalReceipt = "/sdcard/Android/data/${context.packageName}/files/original-receipt.txt"
    shell("rm -f $originalReceipt")
    click()
    val deadline = SystemClock.uptimeMillis() + 8_000
    while (SystemClock.uptimeMillis() < deadline) {
        val result = shell("cat $originalReceipt 2>/dev/null").lines()
        if (result.size == 3) {
            assertEquals(photo.mime, result[0].substringBeforeLast(':'))
            assertTrue(result[0], result[0].substringAfterLast(':').toLong() > 0)
            assertEquals(photo.uri, result[1])
            assertEquals("true", result[2])
            shell("input keyevent 4")
            return
        }
        if (!tap("Just once")) tap("QA图片接收器")
        SystemClock.sleep(200)
    }
    error("The Android viewer did not receive the original MediaStore image")
}
