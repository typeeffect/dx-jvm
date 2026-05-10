package dx.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.net.URLClassLoader
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
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
        assertTrue(error.toString().contains(":1:1: error: unterminated string literal"), error.toString())
        assertTrue(error.toString().contains("^"), error.toString())
    }

    @Test
    fun checkCompilesWithoutExecutingScript() {
        val script = Files.createTempFile("dx-cli-check-", ".dx")
        script.writeText("pair(\"checked\", 1)")

        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exit = DxCli(PrintStream(output), PrintStream(error)).run(arrayOf("check", script.toString()))

        assertEquals(0, exit)
        assertEquals("ok: $script\n", output.toString())
        assertEquals("", error.toString())
    }

    @Test
    fun compileWritesLoadableMainAndSupportClasses() {
        val script = Files.createTempFile("dx-cli-compile-", ".dx")
        script.writeText(
            """
            val prefix = "ok";
            val choose = fun x: Str -> pair(prefix, x);
            choose("compiled")
            """.trimIndent(),
        )
        val outputDirectory = Files.createTempDirectory("dx-cli-classes-")

        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exit = DxCli(PrintStream(output), PrintStream(error)).run(
            arrayOf("compile", script.toString(), "-d", outputDirectory.toString()),
        )

        assertEquals(0, exit)
        assertEquals("", error.toString())
        assertTrue(output.toString().contains("wrote "), output.toString())

        val classFiles = Files.walk(outputDirectory).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "class" }.toList()
        }
        assertEquals(2, classFiles.size)
        val mainClassFile = classFiles.single { it.name.startsWith("Script_") && !it.name.contains("\$Lambda") }
        assertTrue(classFiles.any { it.name.contains("\$Lambda") }, classFiles.toString())

        val binaryName = mainClassFile
            .relativeTo(outputDirectory)
            .toString()
            .removeSuffix(".class")
            .replace('/', '.')
        val loadedResult = URLClassLoader(
            arrayOf(outputDirectory.toUri().toURL()),
            javaClass.classLoader,
        ).use { loader ->
            loader.loadClass(binaryName).getMethod("eval").invoke(null)
        }
        assertEquals(Pair("ok", "compiled"), loadedResult)
    }

    @Test
    fun checkReportsParseDiagnosticsWithSourceSnippet() {
        val script = Files.createTempFile("dx-cli-parse-bad-", ".dx")
        script.writeText("val x = 1 x")

        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exit = DxCli(PrintStream(output), PrintStream(error)).run(arrayOf("check", script.toString()))

        assertEquals(1, exit)
        assertEquals("", output.toString())
        assertTrue(error.toString().contains(":1:11: error: expected `;` or `Eof`, found identifier `x`"), error.toString())
        assertTrue(error.toString().contains("val x = 1 x"), error.toString())
    }

    @Test
    fun checkReportsTypeDiagnosticsWithSourceSnippet() {
        val script = Files.createTempFile("dx-cli-type-bad-", ".dx")
        script.writeText("if 1 then \"then\" else \"else\"")

        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exit = DxCli(PrintStream(output), PrintStream(error)).run(arrayOf("check", script.toString()))

        assertEquals(1, exit)
        assertEquals("", output.toString())
        assertTrue(error.toString().contains(":1:4: error: `if` condition must be Bool"), error.toString())
        assertTrue(error.toString().contains("if 1 then \"then\" else \"else\""), error.toString())
        assertTrue(error.toString().contains("   ^"), error.toString())
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

    @Test
    fun compileRequiresOutputDirectory() {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exit = DxCli(PrintStream(output), PrintStream(error)).run(arrayOf("compile", "missing.dx"))

        assertEquals(2, exit)
        assertEquals("", output.toString())
        assertTrue(error.toString().contains("dx compile <file.dx> -d <output-dir>"), error.toString())
    }
}
