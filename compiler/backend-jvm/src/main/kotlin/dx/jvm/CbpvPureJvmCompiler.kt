package dx.jvm

import dx.cbpv.TypedComputation
import dx.cbpv.TypedValue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ASTORE
import org.objectweb.asm.Opcodes.CHECKCAST
import org.objectweb.asm.Opcodes.DUP
import org.objectweb.asm.Opcodes.GETFIELD
import org.objectweb.asm.Opcodes.INVOKEINTERFACE
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.NEW
import org.objectweb.asm.Opcodes.PUTFIELD
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.V21

data class CbpvJvmCompileResult(
    val generatedClass: GeneratedClass?,
    val supportClasses: List<GeneratedClass> = emptyList(),
    val diagnostics: List<CbpvJvmDiagnostic>,
) {
    val isSuccess: Boolean get() = generatedClass != null && diagnostics.isEmpty()
}

sealed interface CbpvJvmDiagnostic {
    data class UnsupportedComputation(val node: String) : CbpvJvmDiagnostic
    data class UnsupportedValue(val node: String) : CbpvJvmDiagnostic
    data class UnknownVariable(val name: String) : CbpvJvmDiagnostic
}

class CbpvPureJvmCompiler {
    private companion object {
        const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
        const val DX_FUNCTION_INTERNAL_NAME = "dx/jvm/DxFunction"
        const val DX_FUNCTION_APPLY_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;"
    }

    fun compileEvalClass(
        internalName: String,
        source: SourceLocation,
        computation: TypedComputation,
    ): CbpvJvmCompileResult {
        require(source.line > 0) { "source line numbers are 1-based" }
        require(internalName.isNotBlank()) { "internalName must not be blank" }
        require(!internalName.contains('.')) { "internalName must use JVM slash separators" }

        val state = CompilationState(internalName, source)
        val diagnostics = mutableListOf<CbpvJvmDiagnostic>()
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(V21, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, internalName, null, "java/lang/Object", null)
        writer.visitSource(source.fileName, null)
        emitConstructor(writer)

        val method = writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "eval", "()Ljava/lang/Object;", null, null)
        method.visitCode()
        val line = Label()
        method.visitLabel(line)
        method.visitLineNumber(source.line, line)

        val context = EmitContext()
        emitComputation(computation, method, context, state, diagnostics)
        method.visitInsn(ARETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()

        if (diagnostics.isNotEmpty()) {
            return CbpvJvmCompileResult(null, diagnostics = diagnostics)
        }
        return CbpvJvmCompileResult(
            generatedClass = GeneratedClass(internalName, writer.toByteArray()),
            supportClasses = state.supportClasses,
            diagnostics = emptyList(),
        )
    }

    private fun emitComputation(
        computation: TypedComputation,
        method: MethodVisitor,
        context: EmitContext,
        state: CompilationState,
        diagnostics: MutableList<CbpvJvmDiagnostic>,
    ) {
        when (computation) {
            is TypedComputation.Return -> emitValue(computation.value, method, context, state, diagnostics)
            is TypedComputation.Bind -> {
                emitComputation(computation.first, method, context, state, diagnostics)
                val slot = context.allocate(computation.name)
                method.visitVarInsn(ASTORE, slot)
                emitComputation(computation.next, method, context, state, diagnostics)
                context.release(computation.name)
            }
            is TypedComputation.Force -> {
                when (val thunk = computation.thunk) {
                    is TypedValue.ThunkValue -> emitComputation(thunk.computation, method, context, state, diagnostics)
                    else -> diagnostics += CbpvJvmDiagnostic.UnsupportedValue(thunk::class.simpleName ?: "unknown")
                }
            }
            is TypedComputation.Apply -> emitApply(computation, method, context, state, diagnostics)
            is TypedComputation.Perform -> diagnostics += CbpvJvmDiagnostic.UnsupportedComputation("Perform")
            is TypedComputation.Handle -> diagnostics += CbpvJvmDiagnostic.UnsupportedComputation("Handle")
            is TypedComputation.Resume -> diagnostics += CbpvJvmDiagnostic.UnsupportedComputation("Resume")
        }
    }

    private fun emitApply(
        computation: TypedComputation.Apply,
        method: MethodVisitor,
        context: EmitContext,
        state: CompilationState,
        diagnostics: MutableList<CbpvJvmDiagnostic>,
    ) {
        emitValue(computation.function, method, context, state, diagnostics)
        method.visitTypeInsn(CHECKCAST, DX_FUNCTION_INTERNAL_NAME)
        emitValue(computation.argument, method, context, state, diagnostics)
        method.visitMethodInsn(
            INVOKEINTERFACE,
            DX_FUNCTION_INTERNAL_NAME,
            "apply",
            DX_FUNCTION_APPLY_DESCRIPTOR,
            true,
        )
    }

    private fun emitValue(
        value: TypedValue,
        method: MethodVisitor,
        context: EmitContext,
        state: CompilationState,
        diagnostics: MutableList<CbpvJvmDiagnostic>,
    ) {
        when (value) {
            TypedValue.UnitValue -> method.visitInsn(ACONST_NULL)
            is TypedValue.BoolValue -> {
                method.visitLdcInsn(value.value)
                method.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            }
            is TypedValue.IntValue -> {
                method.visitLdcInsn(value.value)
                method.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            }
            is TypedValue.StringValue -> method.visitLdcInsn(value.value)
            is TypedValue.PairValue -> {
                method.visitTypeInsn(NEW, "kotlin/Pair")
                method.visitInsn(DUP)
                emitValue(value.first, method, context, state, diagnostics)
                emitValue(value.second, method, context, state, diagnostics)
                method.visitMethodInsn(
                    INVOKESPECIAL,
                    "kotlin/Pair",
                    "<init>",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V",
                    false,
                )
            }
            is TypedValue.Variable -> {
                emitLoadVariable(value.name, method, context, diagnostics)
            }
            is TypedValue.ThunkValue -> diagnostics += CbpvJvmDiagnostic.UnsupportedValue("ThunkValue")
            is TypedValue.Lambda -> emitLambda(value, method, context, state, diagnostics)
        }
    }

    private fun emitLambda(
        lambda: TypedValue.Lambda,
        method: MethodVisitor,
        context: EmitContext,
        state: CompilationState,
        diagnostics: MutableList<CbpvJvmDiagnostic>,
    ) {
        val freeVariables = freeVariables(lambda.body) - lambda.parameter
        val captures = freeVariables.sorted()
        val closureInternalName = generateClosureClass(lambda, captures, state, diagnostics)

        method.visitTypeInsn(NEW, closureInternalName)
        method.visitInsn(DUP)
        captures.forEach { capture ->
            emitLoadVariable(capture, method, context, diagnostics)
        }
        method.visitMethodInsn(
            INVOKESPECIAL,
            closureInternalName,
            "<init>",
            constructorDescriptor(captures.size),
            false,
        )
    }

    private fun generateClosureClass(
        lambda: TypedValue.Lambda,
        captures: List<String>,
        state: CompilationState,
        diagnostics: MutableList<CbpvJvmDiagnostic>,
    ): String {
        val closureInternalName = "${state.mainInternalName}\$Lambda${state.nextClosureId()}"
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(
            V21,
            ACC_PUBLIC or ACC_FINAL or ACC_SUPER,
            closureInternalName,
            null,
            "java/lang/Object",
            arrayOf(DX_FUNCTION_INTERNAL_NAME),
        )
        writer.visitSource(state.source.fileName, null)

        captures.forEachIndexed { index, _ ->
            writer.visitField(ACC_PRIVATE or ACC_FINAL, captureFieldName(index), OBJECT_DESCRIPTOR, null, null)
                .visitEnd()
        }

        emitClosureConstructor(writer, closureInternalName, captures.size)
        emitClosureApply(writer, closureInternalName, lambda, captures, state, diagnostics)
        writer.visitEnd()
        state.supportClasses += GeneratedClass(closureInternalName, writer.toByteArray())
        return closureInternalName
    }

    private fun emitClosureConstructor(writer: ClassWriter, closureInternalName: String, captureCount: Int) {
        val method = writer.visitMethod(ACC_PUBLIC, "<init>", constructorDescriptor(captureCount), null, null)
        method.visitCode()
        method.visitVarInsn(ALOAD, 0)
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        repeat(captureCount) { index ->
            method.visitVarInsn(ALOAD, 0)
            method.visitVarInsn(ALOAD, index + 1)
            method.visitFieldInsn(PUTFIELD, closureInternalName, captureFieldName(index), OBJECT_DESCRIPTOR)
        }
        method.visitInsn(RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
    }

    private fun emitClosureApply(
        writer: ClassWriter,
        closureInternalName: String,
        lambda: TypedValue.Lambda,
        captures: List<String>,
        state: CompilationState,
        diagnostics: MutableList<CbpvJvmDiagnostic>,
    ) {
        val method = writer.visitMethod(ACC_PUBLIC, "apply", DX_FUNCTION_APPLY_DESCRIPTOR, null, null)
        method.visitCode()
        val line = Label()
        method.visitLabel(line)
        method.visitLineNumber(state.source.line, line)

        val context = EmitContext(
            nextLocal = 2,
            ownerInternalName = closureInternalName,
            captures = captures.mapIndexed { index, name -> name to captureFieldName(index) }.toMap(),
        )
        context.bindExisting(lambda.parameter, 1)
        emitComputation(lambda.body, method, context, state, diagnostics)
        context.release(lambda.parameter)
        method.visitInsn(ARETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
    }

    private fun emitLoadVariable(
        name: String,
        method: MethodVisitor,
        context: EmitContext,
        diagnostics: MutableList<CbpvJvmDiagnostic>,
    ) {
        val slot = context.slot(name)
        if (slot != null) {
            method.visitVarInsn(ALOAD, slot)
            return
        }

        val capture = context.capture(name)
        if (capture != null && context.ownerInternalName != null) {
            method.visitVarInsn(ALOAD, 0)
            method.visitFieldInsn(GETFIELD, context.ownerInternalName, capture, OBJECT_DESCRIPTOR)
            return
        }

        diagnostics += CbpvJvmDiagnostic.UnknownVariable(name)
    }

    private fun emitConstructor(writer: ClassWriter) {
        val method = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
        method.visitCode()
        method.visitVarInsn(ALOAD, 0)
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        method.visitInsn(RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
    }

    private fun constructorDescriptor(argumentCount: Int): String =
        buildString {
            append('(')
            repeat(argumentCount) {
                append(OBJECT_DESCRIPTOR)
            }
            append(")V")
        }

    private fun captureFieldName(index: Int): String = "capture\$$index"
}

private class CompilationState(
    val mainInternalName: String,
    val source: SourceLocation,
) {
    val supportClasses = mutableListOf<GeneratedClass>()
    private var closureCount = 0

    fun nextClosureId(): Int = closureCount++
}

private class EmitContext(
    private var nextLocal: Int = 0,
    val ownerInternalName: String? = null,
    private val captures: Map<String, String> = emptyMap(),
) {
    private val locals = mutableMapOf<String, MutableList<Int>>()

    fun allocate(name: String): Int {
        val slot = nextLocal++
        locals.getOrPut(name) { mutableListOf() }.add(slot)
        return slot
    }

    fun bindExisting(name: String, slot: Int) {
        locals.getOrPut(name) { mutableListOf() }.add(slot)
    }

    fun release(name: String) {
        val stack = locals[name] ?: return
        stack.removeLast()
        if (stack.isEmpty()) {
            locals.remove(name)
        }
    }

    fun slot(name: String): Int? = locals[name]?.lastOrNull()

    fun capture(name: String): String? = captures[name]
}

private fun freeVariables(computation: TypedComputation): Set<String> =
    when (computation) {
        is TypedComputation.Return -> freeVariables(computation.value)
        is TypedComputation.Bind -> freeVariables(computation.first) + (freeVariables(computation.next) - computation.name)
        is TypedComputation.Force -> freeVariables(computation.thunk)
        is TypedComputation.Apply -> freeVariables(computation.function) + freeVariables(computation.argument)
        is TypedComputation.Perform -> computation.arguments.flatMapTo(mutableSetOf()) { freeVariables(it) }
        is TypedComputation.Handle -> freeVariables(computation.body) + computation.handler.clauses.values.flatMapTo(
            mutableSetOf(),
        ) { clause -> freeVariables(clause.body) - clause.parameters.toSet() }
        is TypedComputation.Resume -> freeVariables(computation.value)
    }

private fun freeVariables(value: TypedValue): Set<String> =
    when (value) {
        TypedValue.UnitValue -> emptySet()
        is TypedValue.BoolValue -> emptySet()
        is TypedValue.IntValue -> emptySet()
        is TypedValue.StringValue -> emptySet()
        is TypedValue.PairValue -> freeVariables(value.first) + freeVariables(value.second)
        is TypedValue.Variable -> setOf(value.name)
        is TypedValue.ThunkValue -> freeVariables(value.computation)
        is TypedValue.Lambda -> freeVariables(value.body) - value.parameter
    }
