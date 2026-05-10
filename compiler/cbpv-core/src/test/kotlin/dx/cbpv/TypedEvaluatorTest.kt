package dx.cbpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TypedEvaluatorTest {
    private val evaluator = TypedEvaluator()

    @Test
    fun returnsLiteral() {
        assertDone(RuntimeValue.IntValue(42), evaluator.eval(TypedComputation.Return(TypedValue.IntValue(42))))
    }

    @Test
    fun bindStoresFirstResultInEnvironment() {
        val program = TypedComputation.Bind(
            name = "x",
            first = TypedComputation.Return(TypedValue.StringValue("Ada")),
            next = TypedComputation.Return(TypedValue.Variable("x")),
        )

        assertDone(RuntimeValue.StringValue("Ada"), evaluator.eval(program))
    }

    @Test
    fun ifEvaluatesSelectedBranch() {
        val program = TypedComputation.If(
            condition = TypedValue.BoolValue(false),
            thenBranch = TypedComputation.Return(TypedValue.StringValue("then")),
            elseBranch = TypedComputation.Return(TypedValue.StringValue("else")),
        )

        assertDone(RuntimeValue.StringValue("else"), evaluator.eval(program))
    }

    @Test
    fun ifRejectsNonBoolAtRuntime() {
        val program = TypedComputation.If(
            condition = TypedValue.IntValue(1),
            thenBranch = TypedComputation.Return(TypedValue.StringValue("then")),
            elseBranch = TypedComputation.Return(TypedValue.StringValue("else")),
        )

        assertEquals(RuntimeError.TypeMismatch("bool", RuntimeValue.IntValue(1)), assertFailed(evaluator.eval(program)))
    }

    @Test
    fun forceRunsThunkWithCapturedVariables() {
        val program = TypedComputation.Bind(
            name = "x",
            first = TypedComputation.Return(TypedValue.IntValue(7)),
            next = TypedComputation.Force(
                TypedValue.ThunkValue(TypedComputation.Return(TypedValue.Variable("x"))),
            ),
        )

        assertDone(RuntimeValue.IntValue(7), evaluator.eval(program))
    }

    @Test
    fun applyRunsClosureWithCapturedEnvironment() {
        val program = TypedComputation.Bind(
            name = "captured",
            first = TypedComputation.Return(TypedValue.StringValue("closed")),
            next = TypedComputation.Apply(
                function = TypedValue.Lambda(
                    parameter = "ignored",
                    parameterType = ValueType.UnitType,
                    body = TypedComputation.Return(TypedValue.Variable("captured")),
                ),
                argument = TypedValue.UnitValue,
            ),
        )

        assertDone(RuntimeValue.StringValue("closed"), evaluator.eval(program))
    }

    @Test
    fun unhandledEffectFailsAtRuntime() {
        val result = evaluator.eval(TypedComputation.Perform("Ask", "name"))

        assertEquals(RuntimeError.UnhandledEffect("Ask", "name"), assertFailed(result))
    }

    @Test
    fun handlerCanResumeWithLiteral() {
        val program = handleAsk(
            body = TypedComputation.Perform("Ask", "name"),
            nameClause = TypedComputation.Resume(TypedValue.StringValue("Ada")),
            ageClause = TypedComputation.Resume(TypedValue.IntValue(42)),
        )

        assertDone(RuntimeValue.StringValue("Ada"), evaluator.eval(program))
    }

    @Test
    fun handlerCanAbortWithoutResuming() {
        val program = TypedComputation.Handle(
            body = TypedComputation.Perform("Abort", "stop"),
            handler = TypedHandler(
                effect = "Abort",
                clauses = mapOf(
                    "stop" to TypedHandlerClause(
                        parameters = emptyList(),
                        body = TypedComputation.Return(TypedValue.StringValue("aborted")),
                    ),
                ),
            ),
        )

        assertDone(RuntimeValue.StringValue("aborted"), evaluator.eval(program))
    }

    @Test
    fun operationArgumentsAreBoundInHandlerClause() {
        val program = TypedComputation.Handle(
            body = TypedComputation.Perform("Log", "echo", listOf(TypedValue.StringValue("hello"))),
            handler = TypedHandler(
                effect = "Log",
                clauses = mapOf(
                    "echo" to TypedHandlerClause(
                        parameters = listOf("message"),
                        body = TypedComputation.Resume(TypedValue.Variable("message")),
                    ),
                ),
            ),
        )

        assertDone(RuntimeValue.StringValue("hello"), evaluator.eval(program))
    }

    @Test
    fun bindContinuationReceivesResumeValue() {
        val program = handleAsk(
            body = TypedComputation.Bind(
                name = "name",
                first = TypedComputation.Perform("Ask", "name"),
                next = TypedComputation.Return(TypedValue.Variable("name")),
            ),
            nameClause = TypedComputation.Resume(TypedValue.StringValue("Ada")),
            ageClause = TypedComputation.Resume(TypedValue.IntValue(42)),
        )

        assertDone(RuntimeValue.StringValue("Ada"), evaluator.eval(program))
    }

    @Test
    fun deepHandlerHandlesOperationsAfterResume() {
        val program = handleAsk(
            body = TypedComputation.Bind(
                name = "first",
                first = TypedComputation.Perform("Ask", "name"),
                next = TypedComputation.Bind(
                    name = "second",
                    first = TypedComputation.Perform("Ask", "name"),
                    next = TypedComputation.Return(
                        TypedValue.PairValue(TypedValue.Variable("first"), TypedValue.Variable("second")),
                    ),
                ),
            ),
            nameClause = TypedComputation.Resume(TypedValue.StringValue("Ada")),
            ageClause = TypedComputation.Resume(TypedValue.IntValue(42)),
        )

        assertDone(
            RuntimeValue.PairValue(RuntimeValue.StringValue("Ada"), RuntimeValue.StringValue("Ada")),
            evaluator.eval(program),
        )
    }

    @Test
    fun nearestHandlerWins() {
        val inner = handleAsk(
            body = TypedComputation.Perform("Ask", "name"),
            nameClause = TypedComputation.Resume(TypedValue.StringValue("inner")),
            ageClause = TypedComputation.Resume(TypedValue.IntValue(1)),
        )
        val outer = handleAsk(
            body = inner,
            nameClause = TypedComputation.Resume(TypedValue.StringValue("outer")),
            ageClause = TypedComputation.Resume(TypedValue.IntValue(2)),
        )

        assertDone(RuntimeValue.StringValue("inner"), evaluator.eval(outer))
    }

    @Test
    fun handlerClauseEffectsAreHandledByOuterHandlers() {
        val askInsideLog = TypedComputation.Handle(
            body = handleAsk(
                body = TypedComputation.Perform("Ask", "name"),
                nameClause = TypedComputation.Bind(
                    name = "_",
                    first = TypedComputation.Perform("Log", "unit", listOf(TypedValue.StringValue("handled"))),
                    next = TypedComputation.Return(TypedValue.StringValue("Ada")),
                ),
                ageClause = TypedComputation.Return(TypedValue.StringValue("age")),
            ),
            handler = TypedHandler(
                effect = "Log",
                clauses = mapOf(
                    "unit" to TypedHandlerClause(
                        parameters = listOf("message"),
                        body = TypedComputation.Resume(TypedValue.UnitValue),
                    ),
                ),
            ),
        )

        assertDone(RuntimeValue.StringValue("Ada"), evaluator.eval(askInsideLog))
    }

    @Test
    fun handlerClauseEffectsAreNotHandledBySameHandlerFrame() {
        val program = TypedComputation.Handle(
            body = TypedComputation.Perform("Loop", "again"),
            handler = TypedHandler(
                effect = "Loop",
                clauses = mapOf(
                    "again" to TypedHandlerClause(
                        parameters = emptyList(),
                        body = TypedComputation.Perform("Loop", "again"),
                    ),
                ),
            ),
        )

        assertEquals(RuntimeError.UnhandledEffect("Loop", "again"), assertFailed(evaluator.eval(program)))
    }

    @Test
    fun resumeOutsideHandlerClauseFails() {
        assertEquals(
            RuntimeError.ResumeOutsideHandlerClause,
            assertFailed(evaluator.eval(TypedComputation.Resume(TypedValue.UnitValue))),
        )
    }

    @Test
    fun doubleResumeFails() {
        val program = handleAsk(
            body = TypedComputation.Perform("Ask", "name"),
            nameClause = TypedComputation.Bind(
                name = "first",
                first = TypedComputation.Resume(TypedValue.StringValue("Ada")),
                next = TypedComputation.Resume(TypedValue.StringValue("Grace")),
            ),
            ageClause = TypedComputation.Resume(TypedValue.IntValue(42)),
        )

        assertEquals(RuntimeError.ContinuationAlreadyResumed, assertFailed(evaluator.eval(program)))
    }

    @Test
    fun forceRejectsNonThunk() {
        assertEquals(
            RuntimeError.TypeMismatch("thunk", RuntimeValue.IntValue(1)),
            assertFailed(evaluator.eval(TypedComputation.Force(TypedValue.IntValue(1)))),
        )
    }

    @Test
    fun applyRejectsNonFunction() {
        assertEquals(
            RuntimeError.TypeMismatch("function", RuntimeValue.StringValue("no")),
            assertFailed(evaluator.eval(TypedComputation.Apply(TypedValue.StringValue("no"), TypedValue.UnitValue))),
        )
    }

    @Test
    fun missingOperationFailsAtRuntime() {
        val program = TypedComputation.Handle(
            body = TypedComputation.Perform("Ask", "age"),
            handler = TypedHandler(
                effect = "Ask",
                clauses = mapOf(
                    "name" to TypedHandlerClause(emptyList(), TypedComputation.Resume(TypedValue.StringValue("Ada"))),
                ),
            ),
        )

        assertEquals(RuntimeError.MissingOperation("Ask", "age"), assertFailed(evaluator.eval(program)))
    }

    private fun handleAsk(
        body: TypedComputation,
        nameClause: TypedComputation,
        ageClause: TypedComputation,
    ): TypedComputation =
        TypedComputation.Handle(
            body = body,
            handler = TypedHandler(
                effect = "Ask",
                clauses = mapOf(
                    "name" to TypedHandlerClause(emptyList(), nameClause),
                    "age" to TypedHandlerClause(emptyList(), ageClause),
                ),
            ),
        )

    private fun assertDone(expected: RuntimeValue, result: RuntimeResult) {
        val done = assertIs<RuntimeResult.Done>(result)
        assertEquals(expected, done.value)
    }

    private fun assertFailed(result: RuntimeResult): RuntimeError {
        val failed = assertIs<RuntimeResult.Failed>(result)
        return failed.error
    }
}
