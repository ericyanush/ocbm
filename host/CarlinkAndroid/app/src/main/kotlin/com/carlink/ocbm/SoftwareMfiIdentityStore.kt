package com.carlink.ocbm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Imports a user-selected identity into app-private, non-backed-up storage. */
class SoftwareMfiIdentityStore(private val context: Context) {
    private val root: File get() = File(context.noBackupFilesDir, "offline-mfi")

    fun isReady(): Boolean = runCatching { SoftwareMfiSigner.load(root) }.isSuccess

    internal fun load(): SoftwareMfiSigner = SoftwareMfiSigner.load(root)

    /** Both documents must be selected together. No imported bytes are placed in logs or assets. */
    fun importDocuments(documents: List<Uri>) {
        require(documents.size == 2) { "Select identity.pk8 and certificate.p7b together" }
        val byName = documents.associateBy { uri ->
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
        require(byName.size == 2 && byName.keys == setOf("identity.pk8", "certificate.p7b")) {
            "Select identity.pk8 and certificate.p7b together"
        }
        val staging = File(context.noBackupFilesDir, "offline-mfi-staging")
        check(!staging.exists() || staging.deleteRecursively()) { "Cannot clear previous identity import" }
        check(staging.mkdir()) { "Cannot create private identity directory" }
        try {
            for (name in listOf("identity.pk8", "certificate.p7b")) {
                val uri = requireNotNull(byName[name])
                val target = File(staging, name)
                context.contentResolver.openInputStream(uri)?.use { source ->
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(1024)
                        var total = 0
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 16_384) { "Identity document is too large" }
                            sink.write(buffer, 0, count)
                        }
                        require(total > 0) { "Identity document is empty" }
                    }
                } ?: error("Cannot read selected identity document")
                check(target.setReadable(false, false) && target.setReadable(true, true)) {
                    "Cannot protect identity file"
                }
            }
            SoftwareMfiSigner.load(staging)
            val previous = File(context.noBackupFilesDir, "offline-mfi-previous")
            check(!previous.exists() || previous.deleteRecursively()) { "Cannot clear old identity" }
            if (root.exists()) check(root.renameTo(previous)) { "Cannot stage old identity" }
            if (!staging.renameTo(root)) {
                if (previous.exists()) previous.renameTo(root)
                error("Cannot activate imported identity")
            }
            previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }
}
