package dx.jvm

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ATHROW
import org.objectweb.asm.Opcodes.DUP
import org.objectweb.asm.Opcodes.GETSTATIC
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Opcodes.NEW
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V21

data class SourceLocation(
    val fileName: String,
    val line: Int,
)

data class GeneratedClass(
    val internalName: String,
    val bytecode: ByteArray,
) {
    val binaryName: String = internalName.replace('/', '.')
}

class JvmBytecodeGenerator {
    fun generatePrintMain(
        internalName: String,
        source: SourceLocation,
        message: String,
    ): GeneratedClass =
        generateMainClass(internalName, source) { method ->
            val line = Label()
            method.visitLabel(line)
            method.visitLineNumber(source.line, line)
            method.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
            method.visitLdcInsn(message)
            method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/String;)V",
                false,
            )
            method.visitInsn(RETURN)
        }

    fun generateThrowingMain(
        internalName: String,
        source: SourceLocation,
        message: String,
    ): GeneratedClass =
        generateMainClass(internalName, source) { method ->
            val line = Label()
            method.visitLabel(line)
            method.visitLineNumber(source.line, line)
            method.visitTypeInsn(NEW, "java/lang/RuntimeException")
            method.visitInsn(DUP)
            method.visitLdcInsn(message)
            method.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/RuntimeException",
                "<init>",
                "(Ljava/lang/String;)V",
                false,
            )
            method.visitInsn(ATHROW)
        }

    private fun generateMainClass(
        internalName: String,
        source: SourceLocation,
        emitMainBody: (org.objectweb.asm.MethodVisitor) -> Unit,
    ): GeneratedClass {
        require(source.line > 0) { "source line numbers are 1-based" }
        require(internalName.isNotBlank()) { "internalName must not be blank" }
        require(!internalName.contains('.')) { "internalName must use JVM slash separators" }

        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(V21, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, internalName, null, "java/lang/Object", null)
        writer.visitSource(source.fileName, null)
        emitConstructor(writer)
        emitMain(writer, emitMainBody)
        writer.visitEnd()
        return GeneratedClass(internalName, writer.toByteArray())
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

    private fun emitMain(
        writer: ClassWriter,
        emitMainBody: (org.objectweb.asm.MethodVisitor) -> Unit,
    ) {
        val method = writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
        method.visitCode()
        emitMainBody(method)
        method.visitMaxs(0, 0)
        method.visitEnd()
    }
}

class GeneratedClassLoader(parent: ClassLoader = ClassLoader.getSystemClassLoader()) : ClassLoader(parent) {
    fun define(generatedClass: GeneratedClass): Class<*> =
        defineClass(
            generatedClass.binaryName,
            generatedClass.bytecode,
            0,
            generatedClass.bytecode.size,
        )
}
