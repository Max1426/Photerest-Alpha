package com.photerest.app

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Parses host-provided APK fixtures only. Never launches install/delete or writes a model. */
@RunWith(AndroidJUnit4::class)
class FiInstallerDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val folder get() = File(app.getExternalFilesDir(null),"fi-install-test")
    @Before fun requireFixtures() {
        assumeTrue("Host APK fixtures required for parser checks",listOf("hub.apk","alpha.apk","wrong-signer.apk").all { File(folder,it).isFile })
    }
    @Test fun matchingHubPackageAccepted() { FiComponentInstaller(app).validate(File(folder,"hub.apk")) }
    @Test fun wrongApplicationRejected() {
        try { FiComponentInstaller(app).validate(File(folder,"alpha.apk"));fail("Wrong package accepted") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("请选择")) }
    }
    @Test fun differentSignerRejected() {
        try { FiComponentInstaller(app).validate(File(folder,"wrong-signer.apk"));fail("Untrusted signer accepted") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("签名")) }
    }
}
