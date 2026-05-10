package dx.cli

import dx.cbpv.TypeChecker
import dx.cbpv.TypeEnvironment
import dx.cbpv.allEffects
import dx.frontend.FrontendPipeline
import dx.frontend.SourceId
import dx.jvm.CbpvPureJvmCompiler
import dx.jvm.GeneratedClass
import dx.jvm.GeneratedClassLoader
import dx.jvm.SourceLocation
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

class DxCli(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
    fun run(args: Array<String>): Int {
        if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
            printUsage()
            return if (args.isEmpty()) 2 else 0
        }

        return when (args[0]) {
            "check" -> checkScript(args.drop(1))
            "compile" -> compileToDisk(args.drop(1))
            "run" -> runScript(args.drop(1))
            else -> {
                err.println("error: unknown command `${args[0]}`")
                printUsage(err)
                2
            }
        }
    }

    private fun compileToDisk(args: List<String>): Int {
        val parsed = parseCompileArgs(args) ?: return 2
        val compiled = when (val result = compileScript(listOf(parsed.source.toString()), commandName = "compile")) {
            is CompileAttempt.Success -> result.script
            is CompileAttempt.Failure -> return result.exitCode
        }

        val written = writeClasses(compiled.classes, parsed.outputDirectory)
        written.forEach { path -> out.println("wrote $path") }
        return 0
    }

    private fun checkScript(args: List<String>): Int {
        return when (val result = compileScript(args, commandName = "check")) {
            is CompileAttempt.Success -> {
                out.println("ok: ${result.script.path}")
                0
            }
            is CompileAttempt.Failure -> result.exitCode
        }
    }

    private fun runScript(args: List<String>): Int {
        val compiled = when (val result = compileScript(args, commandName = "run")) {
            is CompileAttempt.Success -> result.script
            is CompileAttempt.Failure -> return result.exitCode
        }

        val classes = GeneratedClassLoader().defineAll(compiled.classes)
        val mainClass = requireNotNull(classes[compiled.internalName]) { "generated main class was not loaded" }
        val result = mainClass.getMethod("eval").invoke(null)
        out.println(DxValuePrinter.render(result))
        return 0
    }

    private fun parseCompileArgs(args: List<String>): CompileArgs? {
        if (args.size != 3 || args[1] != "-d") {
            err.println("error: `compile` expects `<file.dx> -d <output-dir>`")
            printUsage(err)
            return null
        }
        return CompileArgs(source = Path.of(args[0]), outputDirectory = Path.of(args[2]))
    }

    private fun writeClasses(classes: List<GeneratedClass>, outputDirectory: Path): List<Path> {
        outputDirectory.createDirectories()
        return classes.map { generatedClass ->
            val outputPath = outputDirectory.resolve("${generatedClass.internalName}.class")
            outputPath.parent?.createDirectories()
            outputPath.writeBytes(generatedClass.bytecode)
            outputPath
        }
    }

    private fun compileScript(args: List<String>, commandName: String): CompileAttempt {
        if (args.size != 1) {
            err.println("error: `$commandName` expects exactly one .dx file")
            printUsage(err)
            return CompileAttempt.Failure(2)
        }

        val path = Path.of(args.single())
        if (!Files.isRegularFile(path)) {
            err.println("error: source file not found: $path")
            return CompileAttempt.Failure(2)
        }

        val sourceText = path.readText()
        val sourceName = path.name
        val frontend = FrontendPipeline().compile(SourceId(sourceName), sourceText)
        if (!frontend.isSuccess) {
            DxDiagnosticRenderer(sourceText).renderFrontend(frontend).forEach(err::println)
            return CompileAttempt.Failure(1)
        }

        val computation = requireNotNull(frontend.computation)
        val diagnosticRenderer = DxDiagnosticRenderer(sourceText)
        val typecheck = TypeChecker(TypeEnvironment(), frontend.sourceMap).infer(computation)
        if (!typecheck.isSuccess) {
            diagnosticRenderer.renderType(typecheck.reports).forEach(err::println)
            return CompileAttempt.Failure(1)
        }
        val type = requireNotNull(typecheck.type)
        val effects = type.allEffects()
        if (effects.isNotEmpty()) {
            err.println("error: CLI pure runner cannot execute unhandled effects: ${effects.sorted()}")
            return CompileAttempt.Failure(1)
        }

        val internalName = generatedInternalName(path)
        val compiled = CbpvPureJvmCompiler().compileEvalClass(
            internalName = internalName,
            source = SourceLocation(sourceName, 1),
            computation = computation,
        )
        if (!compiled.isSuccess) {
            err.println("jvm backend diagnostics:")
            compiled.diagnostics.forEach { err.println("  $it") }
            return CompileAttempt.Failure(1)
        }

        return CompileAttempt.Success(CompiledScript(path, internalName, compiled.allClasses()))
    }

    private fun printUsage(stream: PrintStream = out) {
        stream.println(
            """
            usage:
              dx check <file.dx>
              dx compile <file.dx> -d <output-dir>
              dx run <file.dx>
            """.trimIndent(),
        )
    }

    private fun generatedInternalName(path: Path): String {
        val absolute = path.absolute().normalize().toString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(absolute.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "dx/generated/cli/Script_$digest"
    }
}

private data class CompileArgs(
    val source: Path,
    val outputDirectory: Path,
)

private sealed interface CompileAttempt {
    data class Success(val script: CompiledScript) : CompileAttempt
    data class Failure(val exitCode: Int) : CompileAttempt
}

private data class CompiledScript(
    val path: Path,
    val internalName: String,
    val classes: List<GeneratedClass>,
)

private fun List<GeneratedClass>.withMain(main: GeneratedClass?): List<GeneratedClass> =
    this + listOfNotNull(main)

private fun dx.jvm.CbpvJvmCompileResult.allClasses(): List<GeneratedClass> =
    supportClasses.withMain(generatedClass)
