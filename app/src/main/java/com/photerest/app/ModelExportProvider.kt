package com.photerest.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Grants access to this one existing model only, never the rest of noBackupFilesDir. */
class ModelExportProvider : ContentProvider() {
    override fun onCreate() = true
    private fun model(uri: Uri): java.io.File {
        if (uri.path != "/model") throw FileNotFoundException("未知模型")
        val app = checkNotNull(context)
        if (!LocalSearchModel.downloaded(app)) throw FileNotFoundException("已有模型不存在或不完整")
        return LocalSearchModel.file(app)
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("模型只读")
        return ParcelFileDescriptor.open(model(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
        val file = model(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> file.name
            OpenableColumns.SIZE -> file.length()
            else -> null
        } }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, where: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, where: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
}
