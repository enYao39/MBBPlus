package com.floatwindow.morebubblebutton

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugLogExporter {
    data class Result(val fileName: String?, val error: String? = null) {
        val success: Boolean get() = fileName != null
    }

    private const val MAX_LOG_BYTES = 20L * 1024L * 1024L
    private const val PROCESS_TIMEOUT_SECONDS = 120L

    fun export(context: Context, treeUri: Uri): Result {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "MoreBubbleButton_$timestamp.zip"
        var documentUri: Uri? = null
        try {
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val directory = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
            documentUri = DocumentsContract.createDocument(
                context.contentResolver,
                directory,
                "application/zip",
                fileName
            ) ?: return Result(null, "The selected folder did not create a file")

            val output = context.contentResolver.openOutputStream(documentUri, "w")
                ?: return Result(null, "Could not open the selected file")
            val commandResults = mutableListOf<String>()
            ZipOutputStream(output).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                putText(zip, "device_info.txt", buildDeviceInfo(context))
                commandResults += captureCommand(
                    zip,
                    "logcat.txt",
                    "logcat -d -v threadtime -b main -b system -b events -b crash"
                )
                commandResults += captureCommand(zip, "dmesg.txt", "dmesg")
                putText(zip, "export_status.txt", commandResults.joinToString("\n", postfix = "\n"))
            }
            return Result(fileName)
        } catch (t: Throwable) {
            try {
                documentUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            } catch (_: Throwable) {
                // Leave cleanup to the user if the document provider cannot delete it.
            }
            return Result(null, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun buildDeviceInfo(context: Context): String = buildString {
        appendLine("MoreBubbleButton diagnostics")
        appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Build display: ${Build.DISPLAY}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Package: ${context.packageName}")
        appendLine("Exported at: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())}")
    }

    private fun captureCommand(zip: ZipOutputStream, entryName: String, command: String): String {
        zip.putNextEntry(ZipEntry(entryName))
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            var copied = 0L
            var truncated = false
            var readFailure: String? = null
            val reader = Thread({
                try {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            val available = MAX_LOG_BYTES - copied
                            if (available > 0) {
                                val writeCount = minOf(count.toLong(), available).toInt()
                                zip.write(buffer, 0, writeCount)
                                copied += writeCount
                                if (writeCount < count) truncated = true
                            } else {
                                truncated = true
                            }
                        }
                    }
                } catch (t: Throwable) {
                    readFailure = t.message ?: t.javaClass.simpleName
                }
            }, "MoreBubble-log-export-reader").apply { isDaemon = true }
            reader.start()
            val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                try { process.inputStream.close() } catch (_: Throwable) {}
                reader.interrupt()
            }
            reader.join(5000L)
            if (reader.isAlive) {
                try { process.inputStream.close() } catch (_: Throwable) {}
                reader.interrupt()
                reader.join(1000L)
            }
            zip.closeEntry()
            when {
                !finished -> "$entryName: timed out after ${PROCESS_TIMEOUT_SECONDS}s"
                readFailure != null -> "$entryName: read error ($readFailure)"
                process.exitValue() != 0 -> "$entryName: command exited with ${process.exitValue()}"
                truncated -> "$entryName: captured first ${MAX_LOG_BYTES / 1024 / 1024} MiB (truncated)"
                else -> "$entryName: captured (${copied / 1024} KiB)"
            }
        } catch (t: Throwable) {
            try {
                zip.write(("Capture failed: ${t.message ?: t.javaClass.simpleName}\n").toByteArray())
                zip.closeEntry()
            } catch (_: IOException) {
                // The ZIP stream itself may already have failed.
            }
            "$entryName: unavailable (${t.message ?: t.javaClass.simpleName})"
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
