package dx.jvm

import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.CheckClassAdapter
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class JvmBytecodeGeneratorTest {
    private val generator = JvmBytecodeGenerator()

    @Test
    fun generatedPrintMainPassesAsmVerification() {
        val generated = generator.generatePrintMain(
            internalName = "dx/generated/Hello",
            source = SourceLocation("hello.dx", 3),
            message = "hello dx",
        )

        assertVerifies(generated)
    }

    @Test
    fun generatedPrintMainRuns() {
        val generated = generator.generatePrintMain(
            internalName = "dx/generated/RunHello",
            source = SourceLocation("hello.dx", 7),
            message = "hello dx",
        )
        val output = captureStdout {
            invokeMain(generated)
        }

        assertEquals("hello dx\n", output)
    }

    @Test
    fun generatedClassUsesRequestedBinaryName() {
        val generated = generator.generatePrintMain(
            internalName = "dx/generated/Named",
            source = SourceLocation("named.dx", 1),
            message = "ok",
        )
        val klass = GeneratedClassLoader().define(generated)

        assertEquals("dx.generated.Named", klass.name)
    }

    @Test
    fun generatedThrowingMainPreservesSourceFileAndLine() {
        val generated = generator.generateThrowingMain(
            internalName = "dx/generated/Throwing",
            source = SourceLocation("throwing.dx", 17),
            message = "boom",
        )

        val error = try {
            invokeMain(generated)
            fail("main should throw")
        } catch (e: InvocationTargetException) {
            e.targetException
        }

        assertEquals("boom", error.message)
        val frame = error.stackTrace.first()
        assertEquals("dx.generated.Throwing", frame.className)
        assertEquals("main", frame.methodName)
        assertEquals("throwing.dx", frame.fileName)
        assertEquals(17, frame.lineNumber)
    }

    @Test
    fun rejectsInvalidSourceLine() {
        val error = kotlin.runCatching {
            generator.generatePrintMain(
                internalName = "dx/generated/BadLine",
                source = SourceLocation("bad.dx", 0),
                message = "bad",
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("source line numbers are 1-based", error.message)
    }

    @Test
    fun rejectsDotSeparatedInternalNames() {
        val error = kotlin.runCatching {
            generator.generatePrintMain(
                internalName = "dx.generated.BadName",
                source = SourceLocation("bad.dx", 1),
                message = "bad",
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("internalName must use JVM slash separators", error.message)
    }

    private fun invokeMain(generated: GeneratedClass) {
        val klass = GeneratedClassLoader().define(generated)
        val main = klass.getMethod("main", Array<String>::class.java)
        main.invoke(null, emptyArray<String>())
    }

    private fun assertVerifies(generated: GeneratedClass) {
        val output = StringWriter()
        CheckClassAdapter.verify(
            ClassReader(generated.bytecode),
            false,
            PrintWriter(output),
        )
        assertEquals("", output.toString())
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
            block()
        } finally {
            System.setOut(original)
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
