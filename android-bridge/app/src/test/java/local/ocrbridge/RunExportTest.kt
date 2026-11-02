package local.ocrbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class RunExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun packsEveryRunFileAndKeepsOnlyTheLatestExport() {
        val runs = folder.newFolder("runs")
        File(runs, "20261102-1").mkdirs()
        File(runs, "20261102-1/ocr.json").writeText("{}")
        File(runs, "20261102-1/photo.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val target = folder.newFolder("exports")
        val first = RunExport.pack(runs, target, "a")!!
        val second = RunExport.pack(runs, target, "b")!!
        assertFalse(first.exists())
        ZipFile(second).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(listOf("runs/20261102-1/ocr.json", "runs/20261102-1/photo.jpg"), names)
            assertEquals(3, zip.getInputStream(zip.getEntry(names[1])).readBytes().size)
        }
    }

    @Test
    fun returnsNothingWithoutRuns() {
        assertNull(RunExport.pack(folder.newFolder("runs"), folder.newFolder("exports"), "a"))
    }
}
