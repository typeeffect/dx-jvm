package dx.cbpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeCheckerTest {
    private val ask = EffectSignature(
        name = "Ask",
        operations = mapOf(
            "name" to OperationSignature(emptyList(), ValueType.StringType),
            "age" to OperationSignature(emptyList(), ValueType.IntType),
        ),
    )
    private val log = EffectSignature(
        name = "Log",
        operations = mapOf(
            "info" to OperationSignature(listOf(ValueType.StringType), ValueType.UnitType),
        ),
    )
    private val async = EffectSignature(
        name = "Async",
        operations = mapOf(
            "awaitInt" to OperationSignature(emptyList(), ValueType.IntType),
        ),
    )
    private val env = TypeEnvironment()
        .withEffect(ask)
        .withEffect(log)
        .withEffect(async)

    private val checker = TypeChecker(env)

    @Test
    fun returnInfersPureComputation() {
        val result = checker.infer(TypedComputation.Return(TypedValue.IntValue(1)))

        assertEquals(ComputationType(ValueType.IntType), result.type)
        assertTrue(result.diagnostics.isEmpty(), "${result.diagnostics}")
    }

    @Test
    fun bindAddsVariableToNextComputation() {
        val program = TypedComputation.Bind(
            name = "x",
            first = TypedComputation.Return(TypedValue.IntValue(1)),
            next = TypedComputation.Return(TypedValue.Variable("x")),
        )

        assertEquals(ComputationType(ValueType.IntType), checker.infer(program).type)
    }

    @Test
    fun bindUnionsEffectsInEvaluationOrder() {
        val program = TypedComputation.Bind(
            name = "name",
            first = TypedComputation.Perform("Ask", "name"),
            next = TypedComputation.Perform("Log", "info", listOf(TypedValue.Variable("name"))),
        )

        assertEquals(
            ComputationType(ValueType.UnitType, setOf("Ask", "Log")),
            checker.infer(program).type,
        )
    }

    @Test
    fun performUnknownEffectIsDiagnostic() {
        val result = checker.infer(TypedComputation.Perform("Db", "query"))

        assertEquals(listOf(TypeDiagnostic.UnknownEffect("Db")), result.diagnostics)
    }

    @Test
    fun performUnknownOperationIsDiagnostic() {
        val result = checker.infer(TypedComputation.Perform("Ask", "email"))

        assertEquals(listOf(TypeDiagnostic.UnknownOperation("Ask", "email")), result.diagnostics)
    }

    @Test
    fun performChecksArgumentArity() {
        val result = checker.infer(TypedComputation.Perform("Log", "info"))

        assertEquals(listOf(TypeDiagnostic.ArityMismatch(expected = 1, actual = 0)), result.diagnostics)
    }

    @Test
    fun performChecksArgumentTypes() {
        val result = checker.infer(
            TypedComputation.Perform("Log", "info", listOf(TypedValue.IntValue(1))),
        )

        assertEquals(
            listOf(TypeDiagnostic.TypeMismatch(ValueType.StringType, ValueType.IntType)),
            result.diagnostics,
        )
    }

    @Test
    fun checkClosedRejectsUnhandledEffects() {
        val result = checker.checkClosed(
            TypedComputation.Perform("Ask", "name"),
            expectedResult = ValueType.StringType,
        )

        assertEquals(listOf(TypeDiagnostic.UnhandledEffects(setOf("Ask"))), result.diagnostics)
    }

    @Test
    fun checkClosedAllowsDeclaredEffects() {
        val result = checker.checkClosed(
            TypedComputation.Perform("Async", "awaitInt"),
            expectedResult = ValueType.IntType,
            allowedEffects = setOf("Async"),
        )

        assertTrue(result.diagnostics.isEmpty(), "${result.diagnostics}")
    }

    @Test
    fun handlerEliminatesHandledEffect() {
        val program = TypedComputation.Handle(
            body = TypedComputation.Perform("Ask", "name"),
            handler = TypedHandler(
                effect = "Ask",
                clauses = mapOf(
                    "name" to TypedHandlerClause(
                        parameters = emptyList(),
                        body = TypedComputation.Return(TypedValue.StringValue("Ada")),
                    ),
                    "age" to TypedHandlerClause(
                        parameters = emptyList(),
                        body = TypedComputation.Return(TypedValue.StringValue("not used")),
                    ),
                ),
            ),
        )

        assertEquals(ComputationType(ValueType.StringType), checker.infer(program).type)
    }

    @Test
    fun handlerClauseCanResumeWithOperationResultType() {
        val program = TypedComputation.Handle(
            body = TypedComputation.Perform("Ask", "name"),
            handler = TypedHandler(
                effect = "Ask",
                clauses = mapOf(
                    "name" to TypedHandlerClause(
                        parameters = emptyList(),
                        body = TypedComputation.Resume(TypedValue.StringValue("Ada")),
                    ),
                    "age" to TypedHandlerClause(
                        parameters = emptyList(),
                        body = TypedComputation.Resume(TypedValue.IntValue(42)),
                    ),
                ),
            ),
        )

        assertEquals(ComputationType(ValueType.StringType), checker.infer(program).type)
    }

    @Test
    fun handlerClauseRejectsResumeWithWrongOperationResultType() {
        val program = TypedComputation.Handle(
            body = TypedComputation.Perform("Ask", "name"),
            handler = TypedHandler(
                effect = "Ask",
                clauses = mapOf(
                    "name" to TypedHandlerClause(
                        parameters = emptyList(),
                        body = TypedComputation.Resume(TypedValue.IntValue(1)),
                    ),
                    "age" to TypedHandlerClause(
                        parameters = emptyList(),
                        body = TypedComputation.Resume(TypedValue.IntValue(42)),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(TypeDiagnostic.ResumeTypeMismatch(ValueType.StringType, ValueType.IntType)),
            checker.infer(program).diagnostics,
        )
    }

    @Test
    fun resumeOutsideHandlerClauseIsDiagnostic() {
        val result = checker.infer(TypedComputation.Resume(TypedValue.UnitValue))

        assertEquals(listOf(TypeDiagnostic.ResumeOutsideHandlerClause), result.diagnostics)
    }

    @Test
    fun handlerRequiresAllOperationClausesForEffect() {
        val program = TypedComputation.Handle(
            body = TypedComputation.Perform("Ask", "name"),
            handler = TypedHandler(
                effect = "Ask",
                clauses = mapOf(
                    "name" to TypedHandlerClause(emptyList(), TypedComputation.Return(TypedValue.StringValue("Ada"))),
                ),
            ),
        )

        assertEquals(
            listOf(TypeDiagnostic.MissingHandlerClause("Ask", "age")),
            checker.infer(program).diagnostics,
        )
    }

    @Test
    fun handlerClauseResultMustMatchHandledBodyResult() {
        val program = TypedComputation.Handle(
            body = TypedComputation.Perform("Ask", "name"),
            handler = TypedHandler(
                effect = "Ask",
                clauses = mapOf(
                    "name" to TypedHandlerClause(emptyList(), TypedComputation.Return(TypedValue.IntValue(1))),
                    "age" to TypedHandlerClause(emptyList(), TypedComputation.Return(TypedValue.StringValue("age"))),
                ),
            ),
        )

        assertEquals(
            listOf(TypeDiagnostic.TypeMismatch(ValueType.StringType, ValueType.IntType)),
            checker.infer(program).diagnostics,
        )
    }

    @Test
    fun handlerClauseEffectsRemainVisible() {
        val program = TypedComputation.Handle(
            body = TypedComputation.Perform("Ask", "name"),
            handler = TypedHandler(
                effect = "Ask",
                clauses = mapOf(
                    "name" to TypedHandlerClause(
                        parameters = emptyList(),
                        body = TypedComputation.Bind(
                            name = "_",
                            first = TypedComputation.Perform("Log", "info", listOf(TypedValue.StringValue("handled"))),
                            next = TypedComputation.Return(TypedValue.StringValue("Ada")),
                        ),
                    ),
                    "age" to TypedHandlerClause(
                        parameters = emptyList(),
                        body = TypedComputation.Return(TypedValue.StringValue("age")),
                    ),
                ),
            ),
        )

        assertEquals(ComputationType(ValueType.StringType, setOf("Log")), checker.infer(program).type)
    }

    @Test
    fun forceThunkReturnsThunkedComputationType() {
        val thunk = TypedValue.ThunkValue(TypedComputation.Perform("Async", "awaitInt"))

        assertEquals(
            ComputationType(ValueType.IntType, setOf("Async")),
            checker.infer(TypedComputation.Force(thunk)).type,
        )
    }

    @Test
    fun forceNonThunkIsDiagnostic() {
        val result = checker.infer(TypedComputation.Force(TypedValue.IntValue(1)))

        assertEquals(listOf(TypeDiagnostic.ForceNonThunk), result.diagnostics)
    }

    @Test
    fun lambdaApplicationReturnsFunctionComputationType() {
        val lambda = TypedValue.Lambda(
            parameter = "x",
            parameterType = ValueType.IntType,
            body = TypedComputation.Return(TypedValue.Variable("x")),
        )

        assertEquals(
            ComputationType(ValueType.IntType),
            checker.infer(TypedComputation.Apply(lambda, TypedValue.IntValue(7))).type,
        )
    }

    @Test
    fun lambdaApplicationChecksArgumentType() {
        val lambda = TypedValue.Lambda(
            parameter = "x",
            parameterType = ValueType.IntType,
            body = TypedComputation.Return(TypedValue.Variable("x")),
        )

        val result = checker.infer(TypedComputation.Apply(lambda, TypedValue.StringValue("bad")))

        assertEquals(
            listOf(TypeDiagnostic.TypeMismatch(ValueType.IntType, ValueType.StringType)),
            result.diagnostics,
        )
    }

    @Test
    fun unknownVariableIsDiagnostic() {
        val result = checker.infer(TypedComputation.Return(TypedValue.Variable("missing")))

        assertEquals(listOf(TypeDiagnostic.UnknownVariable("missing")), result.diagnostics)
    }
}
