package com.photerest.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a dedicated emulator. Does not clear gallery/profile/preferences or contact a provider. */
@RunWith(AndroidJUnit4::class)
class SearchAuthorizationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder get() = File(context.getExternalFilesDir(null), "search-auth-test").apply { mkdirs() }
    @Test fun exportRequest() = runBlocking {
        SearchAuthorization.initialize(context)
        val request = File(folder, "request.json")
        SearchAuthorization.exportRequest(context, Uri.fromFile(request))
        assertTrue(request.length() in 100..16_384)
        assertTrue(request.readText().contains(context.packageName))
    }
    @Test fun importAndVerify() = runBlocking {
        SearchAuthorization.initialize(context)
        SearchAuthorization.importCertificate(context, Uri.fromFile(File(folder, "license.txt")))
        assertTrue(SearchAuthorization.ready)
        val headers = SearchAuthorization.headers("GET", "/v1/quota", byteArrayOf())
        assertTrue(headers.getValue("Authorization").startsWith("Photerest "))
        assertEquals(22, headers.getValue("X-Photerest-Nonce").length)
        assertNotEquals(headers["X-Photerest-Nonce"], SearchAuthorization.headers("GET", "/v1/quota", byteArrayOf())["X-Photerest-Nonce"])
        File(folder, "signed-headers.json").writeText(org.json.JSONObject(headers).toString())
    }
    @Test fun invalidImportPreservesWorkingLicense() = runBlocking {
        SearchAuthorization.initialize(context)
        SearchAuthorization.importCertificate(context, Uri.fromFile(File(folder, "license.txt")))
        val invalid = File(folder, "invalid.txt").apply { writeText("not-a-valid-certificate") }
        try { SearchAuthorization.importCertificate(context, Uri.fromFile(invalid)); fail("Invalid license accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(SearchAuthorization.ready)
        assertTrue(SearchAuthorization.headers("GET", "/v1/quota", byteArrayOf()).containsKey("Authorization"))
    }
    @Test fun foreignLicenseRejected() = runBlocking {
        SearchAuthorization.initialize(context)
        try { SearchAuthorization.importCertificate(context, Uri.fromFile(File(folder, "foreign-license.txt"))); fail("Foreign license accepted") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("另一个应用")) }
    }
    @Test fun persistedLicenseSignsAfterProcessRestart() = runBlocking {
        SearchAuthorization.initialize(context)
        // Headers reload and validate the on-disk certificate even before asynchronous UI state is ready.
        assertTrue(SearchAuthorization.headers("GET", "/v1/quota", byteArrayOf()).containsKey("Authorization"))
    }
    @Test fun missingLicenseDoesNotProduceNetworkCredentials() = runBlocking {
        SearchAuthorization.initialize(context)
        assertFalse(File(context.noBackupFilesDir, "search-license-v1.txt").exists())
        try { SearchAuthorization.headers("GET", "/v1/quota", byteArrayOf()); fail("Unsigned request allowed") }
        catch (error: SearchAuthorizationFailure) { assertEquals("unauthorized", error.code) }
    }
    @Test fun copiedSameAppLicenseRejectedOnAnotherDevice() = runBlocking {
        SearchAuthorization.initialize(context)
        try { SearchAuthorization.importCertificate(context, Uri.fromFile(File(folder, "copied-license.txt"))); fail("Copied device license accepted") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("本机")) }
    }
}
