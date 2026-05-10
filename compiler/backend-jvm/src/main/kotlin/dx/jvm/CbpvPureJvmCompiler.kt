package dx.jvm

import dx.cbpv.TypedComputation
import dx.cbpv.TypedValue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ASTORE
import org.objectweb.asm.Opcodes.DUP
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.NEW
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.V21

data class CbpvJvmCompileResult(
    val generatedClass: GeneratedClass?,
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
    fun compileEvalClass(
        internalName: String,
        source: SourceLocation,
        computation: TypedComputation,
    ): CbpvJvmCompileResult {
        require(source.line > 0) { "source line numbers are 1-based" }
        require(internalName.isNotBlank()) { "internalName must not be blank" }
        require(!internalName.contains('.')) { "internalName must use JVM slash separators" }

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
        emitComputation(computation, method, context, diagnostics)
        method.visitInsn(ARETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()

        if (diagnostics.isNotEmpty()) {
            return CbpvJvmCompileResult(null, diagnostics)
        }
        return CbpvJvmCompileResult(GeneratedClass(internalName, writer.toByteArray()), emptyList())
    }

    private fun emitComputation(
        computation: TypedComputation,
        method: MethodVisitor,
        context: EmitContext,
        diagnostics: MutableList<CbpvJvmDiagnostic>,
    ) {
        when (computation) {
            is TypedComputation.Return -> emitValue(computation.value, method, context, diagnostics)
            is TypedComputation.Bind -> {
                emitComputation(computation.first, method, context, diagnostics)
                val slot = context.allocate(computation.name)
                method.visitVarInsn(ASTORE, slot)
                emitComputation(computation.next, method, context, diagnostics)
                context.release(computation.name)
            }
            is TypedComputation.Force -> {
                when (val thunk = computation.thunk) {
                    is TypedValue.ThunkValue -> emitComputation(thunk.computation, method, context, diagnostics)
                    else -> diagnostics += CbpvJvmDiagnostic.UnsupportedValue(thunk::class.simpleName ?: "unknown")
                }
            }
            is TypedComputation.Apply -> emitApply(computation, method, context, diagnostics)
            is TypedComputation.Perform -> diagnostics += CbpvJvmDiagnostic.UnsupportedComputation("Perform")
            is TypedComputation.Handle -> diagnostics += CbpvJvmDiagnostic.UnsupportedComputation("Handle")
            is TypedComputation.Resume -> diagnostics += CbpvJvmDiagnostic.UnsupportedComputation("Resume")
        }
    }

    private fun emitApply(
        computation: TypedComputation.Apply,
        method: MethodVisitor,
        context: EmitContext,
        diagnostics: MutableList<CbpvJvmDiagnostic>,
    ) {
        val lambda = computation.function as? TypedValue.Lambda
        if (lambda == null) {
            diagnostics += CbpvJvmDiagnostic.UnsupportedValue(computation.function::class.simpleName ?: "unknown")
            return
        }

        emitValue(computation.argument, method, context, diagnostics)
        val slot = context.allocate(lambda.parameter)
        method.visitVarInsn(ASTORE, slot)
        emitComputation(lambda.body, method, context, diagnostics)
        context.release(lambda.parameter)
    }

    private fun emitValue(
        value: TypedValue,
        method: MethodVisitor,
        context: EmitContext,
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
                emitValue(value.first, method, context, diagnostics)
                emitValue(value.second, method, context, diagnostics)
                method.visitMethodInsn(
                    INVOKESPECIAL,
                    "kotlin/Pair",
                    "<init>",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V",
                    false,
                )
            }
            is TypedValue.Variable -> {
                val slot = context.slot(value.name)
                if (slot == null) {
                    diagnostics += CbpvJvmDiagnostic.UnknownVariable(value.name)
                } else {
                    method.visitVarInsn(ALOAD, slot)
                }
            }
            is TypedValue.ThunkValue -> diagnostics += CbpvJvmDiagnostic.UnsupportedValue("ThunkValue")
            is TypedValue.Lambda -> diagnostics += CbpvJvmDiagnostic.UnsupportedValue("Lambda")
        }
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
}

private class EmitContext {
    private val locals = mutableMapOf<String, MutableList<Int>>()
    private var nextLocal = 0

    fun allocate(name: String): Int {
        val slot = nextLocal++
        locals.getOrPut(name) { mutableListOf() }.add(slot)
        return slot
    }

    fun release(name: String) {
        val stack = locals[name] ?: return
        stack.removeLast()
        if (stack.isEmpty()) {
            locals.remove(name)
        }
    }

    fun slot(name: String): Int? = locals[name]?.lastOrNull()
}
