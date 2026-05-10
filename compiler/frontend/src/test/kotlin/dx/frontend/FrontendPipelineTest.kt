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
import kotlin.test.assertIs
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
    fun lexesIfThenElseKeywords() {
        val result = Lexer(SourceId("if.dx"), "if true then \"a\" else \"b\"").lex()

        assertTrue(result.diagnostics.isEmpty(), "${result.diagnostics}")
        assertEquals(
            listOf(
                TokenKind.If,
                TokenKind.True,
                TokenKind.Then,
                TokenKind.String,
                TokenKind.Else,
                TokenKind.String,
                TokenKind.Eof,
            ),
            result.tokens.map { it.kind },
        )
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
    fun parsesIfExpression() {
        val result = pipeline.compile(SourceId("if.dx"), "if false then \"then\" else \"else\"")

        assertSuccess(result)
        assertEquals("else", evalFrontend(result))
        assertEquals("else", compileAndRunJvm(assertNotNull(result.computation)))
    }

    @Test
    fun loweringPreservesSourceMapForTypeDiagnostics() {
        val result = pipeline.compile(SourceId("type_error.dx"), "if 1 then \"then\" else \"else\"")

        assertSuccess(result)
        val typecheck = TypeChecker(TypeEnvironment(), result.sourceMap).infer(assertNotNull(result.computation))

        assertEquals(1, typecheck.reports.single().source?.line)
        assertEquals(4, typecheck.reports.single().source?.column)
    }

    @Test
    fun parsesIfWithBranchComputations() {
        val result = pipeline.compile(
            SourceId("if_block.dx"),
            """
            val useThen = true;
            if useThen then {
              val x = "then";
              x
            } else "else"
            """.trimIndent(),
        )

        assertSuccess(result)
        assertEquals("then", evalFrontend(result))
        assertEquals("then", compileAndRunJvm(assertNotNull(result.computation)))
    }

    @Test
    fun parsesTypedLambdaApplication() {
        val result = pipeline.compile(SourceId("lambda.dx"), "(fun x: Str -> x)(\"Ada\")")

        assertSuccess(result)
        assertEquals("Ada", evalFrontend(result))
        assertEquals("Ada", compileAndRunJvm(assertNotNull(result.computation)))
    }

    @Test
    fun lowersLambdaApplicationThroughThunkForceAndComputationLambda() {
        val result = pipeline.compile(SourceId("lambda.dx"), "(fun x: Str -> x)(\"Ada\")")

        assertSuccess(result)
        assertEquals(
            TypedComputation.Bind(
                name = "\$dx_fn_0",
                first = TypedComputation.Return(
                    dx.cbpv.TypedValue.ThunkValue(
                        TypedComputation.Lambda(
                            parameter = "x",
                            parameterType = ValueType.StringType,
                            body = TypedComputation.Return(dx.cbpv.TypedValue.Variable("x")),
                        ),
                    ),
                ),
                next = TypedComputation.Bind(
                    name = "\$dx_arg_1",
                    first = TypedComputation.Return(dx.cbpv.TypedValue.StringValue("Ada")),
                    next = TypedComputation.Apply(
                        function = TypedComputation.Force(dx.cbpv.TypedValue.Variable("\$dx_fn_0")),
                        argument = dx.cbpv.TypedValue.Variable("\$dx_arg_1"),
                    ),
                ),
            ),
            result.computation,
        )
    }

    @Test
    fun parsesTypedLambdaInValBinding() {
        val result = pipeline.compile(SourceId("lambda_val.dx"), "val id = fun x: Str -> x; id(\"Ada\")")

        assertSuccess(result)
        assertEquals("Ada", evalFrontend(result))
        assertEquals("Ada", compileAndRunJvm(assertNotNull(result.computation)))
    }

    @Test
    fun parsesClosureCaptureForJvm() {
        val result = pipeline.compile(
            SourceId("closure.dx"),
            """
            val prefix = "Ada";
            val combine = fun x: Str -> pair(prefix, x);
            combine("Lovelace")
            """.trimIndent(),
        )

        assertSuccess(result)
        assertEquals(Pair("Ada", "Lovelace"), evalFrontend(result))
        assertEquals(Pair("Ada", "Lovelace"), compileAndRunJvm(assertNotNull(result.computation)))
    }

    @Test
    fun parsesNestedClosureCaptureForJvm() {
        val result = pipeline.compile(
            SourceId("nested_closure.dx"),
            """
            val inner = (fun x: Str -> fun y: Str -> pair(x, y))("Ada");
            inner("Lovelace")
            """.trimIndent(),
        )

        assertSuccess(result)
        assertEquals(Pair("Ada", "Lovelace"), evalFrontend(result))
        assertEquals(Pair("Ada", "Lovelace"), compileAndRunJvm(assertNotNull(result.computation)))
    }

    @Test
    fun parserRejectsLambdaWithoutParameterType() {
        val result = pipeline.compile(SourceId("lambda.dx"), "fun x -> x")

        assertTrue(result.parseDiagnostics.isNotEmpty(), result.toString())
    }

    @Test
    fun parserRejectsUnknownParameterType() {
        val result = pipeline.compile(SourceId("lambda.dx"), "fun x: Unknown -> x")

        val diagnostic = assertIs<ParseDiagnostic.Expected>(
            result.parseDiagnostics.first { it is ParseDiagnostic.Expected && it.expected == "known type" },
        )
        assertEquals("known type", diagnostic.expected)
        assertEquals("Unknown", diagnostic.actual.lexeme)
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
        val classes = GeneratedClassLoader().defineAll(compiled.supportClasses + listOf(assertNotNull(compiled.generatedClass)))
        val klass = assertNotNull(classes["dx/generated/frontend/Program"])
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
