package io.github.foadsf.parallax

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exporter {
    /** Zips the whole scan folder (a ready COLMAP project plus PLY clouds) and returns the chooser Intent. */
    fun prepareShareIntent(ctx: Context, scan: Scan): Intent {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val zip = File(dir, "parallax-${scan.name}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            scan.dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                z.putNextEntry(ZipEntry("parallax-${scan.name}/" + f.relativeTo(scan.dir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData carries the read grant to the system chooser too (for its preview).
            clipData = ClipData.newRawUri(zip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share scan")
    }

    /** Copies the selected (or best) point cloud to the public Downloads folder; returns its display name. */
    fun saveToDownloads(ctx: Context, scan: Scan, preferDense: Boolean = true): String? {
        val src = (if (preferDense) scan.bestCloud else scan.sparse.takeIf { it.exists() } ?: scan.bestCloud) ?: return null
        val name = "parallax-${scan.name}-${src.nameWithoutExtension}.ply"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val out = resolver.openOutputStream(uri) ?: run { resolver.delete(uri, null, null); return null }
            out.use { stream -> src.inputStream().use { it.copyTo(stream) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            name
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
