package local.ocrbridge

import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs the run log into one zip for sharing; photos are already compressed, so speed wins. */
internal object RunExport {
    fun pack(runs: File, target: File, stamp: String): File? {
        val files = runs.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        if (files.isEmpty()) return null
        target.mkdirs()
        // Only the latest export is kept; these hold private receipts.
        target.listFiles().orEmpty().forEach { it.delete() }
        val archive = File(target, "receipt-runs-$stamp.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            for (file in files) {
                zip.putNextEntry(ZipEntry("runs/" + file.relativeTo(runs).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return archive
    }
}
