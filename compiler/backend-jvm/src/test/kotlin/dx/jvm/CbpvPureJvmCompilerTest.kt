package dx.jvm

import dx.cbpv.RuntimeResult
import dx.cbpv.RuntimeValue
import dx.cbpv.SelectiveLoweringClass
import dx.cbpv.SelectiveLoweringReason
import dx.cbpv.TypedComputation
import dx.cbpv.TypedEvaluator
import dx.cbpv.TypedValue
import dx.cbpv.ValueType
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.CheckClassAdapter
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CbpvPureJvmCompilerTest {
    private val compiler = CbpvPureJvmCompiler()
    private val evaluator = TypedEvaluator()

    @Test
    fun compilesPureStringReturn() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/StringReturn",
            computation = TypedComputation.Return(TypedValue.StringValue("hello")),
        )
    }

    @Test
    fun compilesPureIntReturnAsBoxedLong() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/IntReturn",
            computation = TypedComputation.Return(TypedValue.IntValue(42)),
        )
    }

    @Test
    fun compilesPureBoolReturnAsBoxedBoolean() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/BoolReturn",
            computation = TypedComputation.Return(TypedValue.BoolValue(true)),
        )
    }

    @Test
    fun compilesUnitReturnAsNull() {
        val result = compileAndEval(
            "dx/generated/cbpv/UnitReturn",
            TypedComputation.Return(TypedValue.UnitValue),
        )

        assertEquals(null, result)
    }

    @Test
    fun compilesPairReturnAsKotlinPair() {
        val result = compileAndEval(
            "dx/generated/cbpv/PairReturn",
            TypedComputation.Return(
                TypedValue.PairValue(
                    TypedValue.StringValue("left"),
                    TypedValue.IntValue(9),
                ),
            ),
        )

        assertEquals(Pair("left", 9L), result)
    }

    @Test
    fun compilesBindWithVariableUse() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/Bind",
            computation = TypedComputation.Bind(
                name = "x",
                first = TypedComputation.Return(TypedValue.StringValue("bound")),
                next = TypedComputation.Return(TypedValue.Variable("x")),
            ),
        )
    }

    @Test
    fun compilesIfThenBranch() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/IfThen",
            computation = TypedComputation.If(
                condition = TypedValue.BoolValue(true),
                thenBranch = TypedComputation.Return(TypedValue.StringValue("then")),
                elseBranch = TypedComputation.Return(TypedValue.StringValue("else")),
            ),
        )
    }

    @Test
    fun compilesIfElseBranchWithVariableCondition() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/IfElseVariableCondition",
            computation = TypedComputation.Bind(
                name = "condition",
                first = TypedComputation.Return(TypedValue.BoolValue(false)),
                next = TypedComputation.If(
                    condition = TypedValue.Variable("condition"),
                    thenBranch = TypedComputation.Return(TypedValue.StringValue("then")),
                    elseBranch = TypedComputation.Return(TypedValue.StringValue("else")),
                ),
            ),
        )
    }

    @Test
    fun compilesNestedBindWithShadowing() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/NestedBind",
            computation = TypedComputation.Bind(
                name = "x",
                first = TypedComputation.Return(TypedValue.StringValue("outer")),
                next = TypedComputation.Bind(
                    name = "x",
                    first = TypedComputation.Return(TypedValue.StringValue("inner")),
                    next = TypedComputation.Return(TypedValue.Variable("x")),
                ),
            ),
        )
    }

    @Test
    fun restoresOuterBindingAfterNestedShadowing() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/RestoreOuterBinding",
            computation = TypedComputation.Bind(
                name = "x",
                first = TypedComputation.Return(TypedValue.StringValue("outer")),
                next = TypedComputation.Bind(
                    name = "_",
                    first = TypedComputation.Bind(
                        name = "x",
                        first = TypedComputation.Return(TypedValue.StringValue("inner")),
                        next = TypedComputation.Return(TypedValue.Variable("x")),
                    ),
                    next = TypedComputation.Return(TypedValue.Variable("x")),
                ),
            ),
        )
    }

    @Test
    fun compilesForceOfLiteralThunk() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/ForceThunk",
            computation = TypedComputation.Force(
                TypedValue.ThunkValue(TypedComputation.Return(TypedValue.StringValue("forced"))),
            ),
        )
    }

    @Test
    fun compilesApplyOfLiteralLambda() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/ApplyLambda",
            computation = TypedComputation.Apply(
                function = TypedComputation.Lambda(
                    parameter = "x",
                    parameterType = ValueType.StringType,
                    body = TypedComputation.Return(TypedValue.Variable("x")),
                ),
                argument = TypedValue.StringValue("argument"),
            ),
        )
    }

    @Test
    fun compilesLambdaStoredInVariable() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/LambdaStoredInVariable",
            computation = TypedComputation.Bind(
                name = "id",
                first = TypedComputation.Return(
                    TypedValue.ThunkValue(
                        TypedComputation.Lambda(
                            parameter = "x",
                            parameterType = ValueType.StringType,
                            body = TypedComputation.Return(TypedValue.Variable("x")),
                        ),
                    ),
                ),
                next = TypedComputation.Apply(
                    function = TypedComputation.Force(TypedValue.Variable("id")),
                    argument = TypedValue.StringValue("Ada"),
                ),
            ),
        )
    }

    @Test
    fun compilesClosureCapture() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/ClosureCapture",
            computation = TypedComputation.Bind(
                name = "prefix",
                first = TypedComputation.Return(TypedValue.StringValue("Ada")),
                next = TypedComputation.Bind(
                    name = "combine",
                    first = TypedComputation.Return(
                        TypedValue.ThunkValue(
                            TypedComputation.Lambda(
                                parameter = "x",
                                parameterType = ValueType.StringType,
                                body = TypedComputation.Return(
                                    TypedValue.PairValue(
                                        TypedValue.Variable("prefix"),
                                        TypedValue.Variable("x"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    next = TypedComputation.Apply(
                        function = TypedComputation.Force(TypedValue.Variable("combine")),
                        argument = TypedValue.StringValue("Lovelace"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun compilesNestedClosureCapturingOuterParameter() {
        assertJvmMatchesInterpreter(
            className = "dx/generated/cbpv/NestedClosureCapture",
            computation = TypedComputation.Bind(
                name = "inner",
                first = TypedComputation.Apply(
                    function = TypedComputation.Lambda(
                        parameter = "x",
                        parameterType = ValueType.StringType,
                        body = TypedComputation.Return(
                            TypedValue.ThunkValue(
                                TypedComputation.Lambda(
                                    parameter = "y",
                                    parameterType = ValueType.StringType,
                                    body = TypedComputation.Return(
                                        TypedValue.PairValue(
                                            TypedValue.Variable("x"),
                                            TypedValue.Variable("y"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    argument = TypedValue.StringValue("Ada"),
                ),
                next = TypedComputation.Apply(
                    function = TypedComputation.Force(TypedValue.Variable("inner")),
                    argument = TypedValue.StringValue("Lovelace"),
                ),
            ),
        )
    }

    @Test
    fun generatedPureClassPassesAsmVerification() {
        val result = compile(
            "dx/generated/cbpv/Verify",
            TypedComputation.Return(TypedValue.StringValue("verified")),
        )

        val output = StringWriter()
        result.allClasses().forEach { generated ->
            CheckClassAdapter.verify(ClassReader(generated.bytecode), false, PrintWriter(output))
        }
        assertEquals("", output.toString())
    }

    @Test
    fun generatedClosureClassesPassAsmVerification() {
        val result = compile(
            "dx/generated/cbpv/VerifyClosure",
            TypedComputation.Apply(
                function = TypedComputation.Lambda(
                    parameter = "x",
                    parameterType = ValueType.StringType,
                    body = TypedComputation.Return(TypedValue.Variable("x")),
                ),
                argument = TypedValue.StringValue("verified"),
            ),
        )

        assertTrue(result.supportClasses.isNotEmpty())
        val output = StringWriter()
        result.allClasses().forEach { generated ->
            CheckClassAdapter.verify(ClassReader(generated.bytecode), false, PrintWriter(output))
        }
        assertEquals("", output.toString())
    }

    @Test
    fun rejectsEffectfulPerformInPureJvmSubset() {
        val result = compiler.compileEvalClass(
            internalName = "dx/generated/cbpv/RejectPerform",
            source = SourceLocation("reject.dx", 1),
            computation = TypedComputation.Perform("Ask", "name"),
        )

        val diagnostic = assertSingleUnsupportedLoweringPlan(result)
        assertEquals(SelectiveLoweringClass.OneShotCapture, diagnostic.loweringClass)
        assertTrue(
            diagnostic.reasons.any {
                it is SelectiveLoweringReason.OperationRequiresCapture &&
                    it.effect == "Ask" &&
                    it.operation == "name"
            },
            "${diagnostic.reasons}",
        )
        assertEquals(null, result.generatedClass)
    }

    @Test
    fun rejectsDirectHandlerFrameInPureJvmSubsetBeforeBytecodeEmission() {
        val result = compiler.compileEvalClass(
            internalName = "dx/generated/cbpv/RejectHandler",
            source = SourceLocation("reject.dx", 1),
            computation = TypedComputation.Handle(
                body = TypedComputation.Perform("Ask", "name"),
                handler = dx.cbpv.TypedHandler(
                    effect = "Ask",
                    clauses = mapOf(
                        "name" to dx.cbpv.TypedHandlerClause(
                            parameters = emptyList(),
                            body = TypedComputation.Resume(TypedValue.StringValue("Ada")),
                        ),
                    ),
                ),
            ),
        )

        val diagnostic = assertSingleUnsupportedLoweringPlan(result)
        assertEquals(SelectiveLoweringClass.DirectWithHandlerFrame, diagnostic.loweringClass)
        assertTrue(diagnostic.reasons.any { it is SelectiveLoweringReason.HandlerFrame }, "${diagnostic.reasons}")
        assertEquals(null, result.generatedClass)
    }

    @Test
    fun rejectsAsyncSuspendInPureJvmSubsetBeforeBytecodeEmission() {
        val result = compiler.compileEvalClass(
            internalName = "dx/generated/cbpv/RejectAsync",
            source = SourceLocation("reject.dx", 1),
            computation = TypedComputation.Perform("Async", "awaitInt"),
        )

        val diagnostic = assertSingleUnsupportedLoweringPlan(result)
        assertEquals(SelectiveLoweringClass.AsyncSuspend, diagnostic.loweringClass)
        assertTrue(
            diagnostic.reasons.any {
                it is SelectiveLoweringReason.AsyncOperation &&
                    it.effect == "Async" &&
                    it.operation == "awaitInt"
            },
            "${diagnostic.reasons}",
        )
        assertEquals(null, result.generatedClass)
    }

    @Test
    fun rejectsUnknownVariable() {
        val result = compiler.compileEvalClass(
            internalName = "dx/generated/cbpv/UnknownVariable",
            source = SourceLocation("bad.dx", 1),
            computation = TypedComputation.Return(TypedValue.Variable("missing")),
        )

        assertEquals(
            listOf(CbpvJvmDiagnostic.UnknownVariable("missing")),
            result.diagnostics,
        )
        assertEquals(null, result.generatedClass)
    }

    private fun assertJvmMatchesInterpreter(className: String, computation: TypedComputation) {
        val expected = normalizeInterpreterResult(evaluator.eval(computation))
        val actual = compileAndEval(className, computation)

        assertEquals(expected, actual)
    }

    private fun compileAndEval(className: String, computation: TypedComputation): Any? {
        val result = compile(className, computation)
        val classes = GeneratedClassLoader().defineAll(result.allClasses())
        val klass = assertNotNull(classes[className])
        return klass.getMethod("eval").invoke(null)
    }

    private fun compile(className: String, computation: TypedComputation): CbpvJvmCompileResult {
        val result = compiler.compileEvalClass(
            internalName = className,
            source = SourceLocation("cbpv.dx", 11),
            computation = computation,
        )
        assertTrue(result.diagnostics.isEmpty(), "${result.diagnostics}")
        assertNotNull(result.generatedClass)
        return result
    }

    private fun assertSingleUnsupportedLoweringPlan(
        result: CbpvJvmCompileResult,
    ): CbpvJvmDiagnostic.UnsupportedLoweringPlan {
        assertEquals(1, result.diagnostics.size, "${result.diagnostics}")
        return result.diagnostics.single() as CbpvJvmDiagnostic.UnsupportedLoweringPlan
    }

    private fun normalizeInterpreterResult(result: RuntimeResult): Any? =
        when (result) {
            is RuntimeResult.Done -> normalizeRuntimeValue(result.value)
            is RuntimeResult.Failed -> error("interpreter failed: ${result.error}")
        }

    private fun normalizeRuntimeValue(value: RuntimeValue): Any? =
        when (value) {
            RuntimeValue.UnitValue -> null
            is RuntimeValue.BoolValue -> value.value
            is RuntimeValue.IntValue -> value.value
            is RuntimeValue.StringValue -> value.value
            is RuntimeValue.PairValue -> Pair(
                normalizeRuntimeValue(value.first),
                normalizeRuntimeValue(value.second),
            )
            is RuntimeValue.ThunkValue -> error("thunk result is not part of the pure JVM value subset")
            is RuntimeValue.ClosureValue -> error("closure result is not part of the pure JVM value subset")
        }
}

private fun CbpvJvmCompileResult.allClasses(): List<GeneratedClass> =
    supportClasses + listOfNotNull(generatedClass)
