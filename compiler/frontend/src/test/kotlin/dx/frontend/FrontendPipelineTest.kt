package dx.frontend

import dx.cbpv.ComputationType
import dx.cbpv.RuntimeResult
import dx.cbpv.RuntimeValue
import dx.cbpv.TypeChecker
import dx.cbpv.TypeEnvironment
import dx.cbpv.TypedComputation
import dx.cbpv.TypedEvaluator
import dx.cbpv.ValueType
import dx.jvm.CbpvPureJvmCompiler
import dx.jvm.GeneratedClassLoader
import dx.jvm.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FrontendPipelineTest {
    private val pipeline = FrontendPipeline()

    @Test
    fun lexesKeywordsIdentifiersAndLiteralsWithSpans() {
        val result = Lexer(SourceId("lexer.dx"), "val answer = 42; answer").lex()

        assertTrue(result.diagnostics.isEmpty(), "${result.diagnostics}")
        assertEquals(
            listOf(
                TokenKind.Val,
                TokenKind.Identifier,
                TokenKind.Equal,
                TokenKind.Integer,
                TokenKind.Semicolon,
                TokenKind.Identifier,
                TokenKind.Eof,
            ),
            result.tokens.map { it.kind },
        )
        assertEquals(1, result.tokens.first().span.line)
        assertEquals(1, result.tokens.first().span.column)
        assertEquals(5, result.tokens[1].span.column)
    }

    @Test
    fun parsesAndLowersValSequenceToCbpvBind() {
        val result = pipeline.compile(SourceId("bind.dx"), "val x = \"hello\"; x")

        assertSuccess(result)
        assertEquals(
            TypedComputation.Bind(
                name = "x",
                first = TypedComputation.Return(dx.cbpv.TypedValue.StringValue("hello")),
                next = TypedComputation.Return(dx.cbpv.TypedValue.Variable("x")),
            ),
            result.computation,
        )
    }

    @Test
    fun parsesThunkAndForce() {
        val result = pipeline.compile(SourceId("thunk.dx"), "force thunk { \"forced\" }")

        assertSuccess(result)
        assertEquals("forced", evalFrontend(result))
    }

    @Test
    fun parsesPairs() {
        val result = pipeline.compile(SourceId("pair.dx"), "pair(\"left\", 9)")

        assertSuccess(result)
        assertEquals(Pair("left", 9L), evalFrontend(result))
    }

    @Test
    fun rejectsUnsupportedUntypedLambdaInLowering() {
        val result = pipeline.compile(SourceId("lambda.dx"), "fun x -> x")

        assertEquals(
            listOf(
                LowerDiagnostic.UnsupportedExpression(
                    "lambda requires parameter type syntax before lowering",
                    assertNotNull(result.module).expression.span,
                ),
            ),
            result.lowerDiagnostics,
        )
    }

    @Test
    fun frontendTypecheckInterpreterAndJvmAgreeForPureProgram() {
        val result = pipeline.compile(
            SourceId("program.dx"),
            """
            val x = "outer";
            val ignored = {
              val x = "inner";
              x
            };
            pair(x, ignored)
            """.trimIndent(),
        )

        assertSuccess(result)
        val computation = assertNotNull(result.computation)
        assertEquals(
            ComputationType(
                ValueType.PairType(ValueType.StringType, ValueType.StringType),
            ),
            TypeChecker(TypeEnvironment()).infer(computation).type,
        )

        val interpreted = normalizeInterpreterResult(TypedEvaluator().eval(computation))
        val jvm = compileAndRunJvm(computation)
        assertEquals(interpreted, jvm)
        assertEquals(Pair("outer", "inner"), jvm)
    }

    @Test
    fun parserReportsMissingSemicolonBetweenExpressions() {
        val result = pipeline.compile(SourceId("bad.dx"), "\"a\" \"b\"")

        assertTrue(result.parseDiagnostics.isNotEmpty())
    }

    @Test
    fun lexerReportsUnterminatedString() {
        val result = pipeline.compile(SourceId("bad.dx"), "\"unterminated")

        assertTrue(result.lexDiagnostics.single() is LexDiagnostic.UnterminatedString)
    }

    private fun assertSuccess(result: FrontendResult) {
        assertTrue(result.isSuccess, result.toString())
    }

    private fun evalFrontend(result: FrontendResult): Any? =
        normalizeInterpreterResult(TypedEvaluator().eval(assertNotNull(result.computation)))

    private fun compileAndRunJvm(computation: TypedComputation): Any? {
        val compiled = CbpvPureJvmCompiler().compileEvalClass(
            internalName = "dx/generated/frontend/Program",
            source = SourceLocation("program.dx", 1),
            computation = computation,
        )
        assertTrue(compiled.diagnostics.isEmpty(), "${compiled.diagnostics}")
        val klass = GeneratedClassLoader().define(assertNotNull(compiled.generatedClass))
        return klass.getMethod("eval").invoke(null)
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
            is RuntimeValue.ThunkValue -> error("unexpected thunk result")
            is RuntimeValue.ClosureValue -> error("unexpected closure result")
        }
}
