package dx.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxCliTest {
    @Test
    fun runExecutesPureScriptThroughJvmBackend() {
        val script = Files.createTempFile("dx-cli-", ".dx")
        script.writeText(
            """
            val useThen = true;
            val choose = fun x: Str -> if useThen then pair("ok", x) else pair("bad", x);
            choose("cli")
            """.trimIndent(),
        )

        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exit = DxCli(PrintStream(output), PrintStream(error)).run(arrayOf("run", script.toString()))

        assertEquals(0, exit)
        assertEquals("pair(ok, cli)\n", output.toString())
        assertEquals("", error.toString())
    }

    @Test
    fun runReportsFrontendDiagnostics() {
        val script = Files.createTempFile("dx-cli-bad-", ".dx")
        script.writeText("\"unterminated")

        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exit = DxCli(PrintStream(output), PrintStream(error)).run(arrayOf("run", script.toString()))

        assertEquals(1, exit)
        assertEquals("", output.toString())
        assertTrue(error.toString().contains("frontend diagnostics:"), error.toString())
    }

    @Test
    fun missingArgumentsReturnUsageError() {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exit = DxCli(PrintStream(output), PrintStream(error)).run(emptyArray())

        assertEquals(2, exit)
        assertTrue(output.toString().contains("dx run <file.dx>"), output.toString())
        assertEquals("", error.toString())
    }
}
