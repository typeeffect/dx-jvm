package dx.cbpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EvaluatorTest {
    private val evaluator = Evaluator()

    @Test
    fun returnsUnit() {
        assertDone(unit, evaluator.eval(ret(unit)))
    }

    @Test
    fun returnsInteger() {
        assertDone(int(42), evaluator.eval(ret(int(42))))
    }

    @Test
    fun bindPassesReturnedValue() {
        val program = bind(ret(int(10))) { value ->
            val n = (value as Value.IntValue).value
            ret(int(n + 1))
        }

        assertDone(int(11), evaluator.eval(program))
    }

    @Test
    fun nestedBindPreservesSequencing() {
        val program = bind(ret(int(2))) { a ->
            bind(ret(int(3))) { b ->
                ret(int((a as Value.IntValue).value * (b as Value.IntValue).value))
            }
        }

        assertDone(int(6), evaluator.eval(program))
    }

    @Test
    fun thunkDoesNotRunUntilForced() {
        val lazy = thunk(perform("Ask", "value"))

        assertDone(lazy, evaluator.eval(ret(lazy)))
    }

    @Test
    fun forceRunsThunkedComputation() {
        val program = force(thunk(ret(str("forced"))))

        assertDone(str("forced"), evaluator.eval(program))
    }

    @Test
    fun forceRejectsNonThunk() {
        val error = assertFailed(evaluator.eval(force(int(1))))

        assertEquals(EvalError.TypeMismatch("thunk", int(1)), error)
    }

    @Test
    fun applyRunsFunctionValue() {
        val function = Value.FunctionValue { argument ->
            val n = (argument as Value.IntValue).value
            ret(int(n * 2))
        }

        assertDone(int(14), evaluator.eval(Computation.Apply(function, int(7))))
    }

    @Test
    fun applyRejectsNonFunction() {
        val error = assertFailed(evaluator.eval(Computation.Apply(str("nope"), unit)))

        assertEquals(EvalError.TypeMismatch("function", str("nope")), error)
    }

    @Test
    fun unhandledOperationFails() {
        val error = assertFailed(evaluator.eval(perform("Ask", "value")))

        assertEquals(EvalError.UnhandledEffect("Ask", "value"), error)
    }

    @Test
    fun handlerResumesOperation() {
        val program = handle(
            perform("Ask", "name"),
            Handler(
                effect = "Ask",
                operations = mapOf(
                    "name" to OperationClause { _, k -> k.resume(str("Ada")) },
                ),
            ),
        )

        assertDone(str("Ada"), evaluator.eval(program))
    }

    @Test
    fun handlerCanUseOperationArguments() {
        val program = handle(
            perform("Console", "echo", listOf(str("hello"))),
            Handler(
                effect = "Console",
                operations = mapOf(
                    "echo" to OperationClause { args, k -> k.resume(args.single()) },
                ),
            ),
        )

        assertDone(str("hello"), evaluator.eval(program))
    }

    @Test
    fun missingOperationInMatchingHandlerFails() {
        val program = handle(
            perform("Ask", "age"),
            Handler(effect = "Ask", operations = emptyMap()),
        )

        val error = assertFailed(evaluator.eval(program))
        assertEquals(EvalError.MissingOperation("Ask", "age"), error)
    }

    @Test
    fun nearestHandlerWins() {
        val outer = Handler(
            effect = "Ask",
            operations = mapOf("name" to OperationClause { _, k -> k.resume(str("outer")) }),
        )
        val inner = Handler(
            effect = "Ask",
            operations = mapOf("name" to OperationClause { _, k -> k.resume(str("inner")) }),
        )

        val program = handle(handle(perform("Ask", "name"), inner), outer)

        assertDone(str("inner"), evaluator.eval(program))
    }

    @Test
    fun outerHandlerCanCatchDifferentEffect() {
        val program = handle(
            handle(
                perform("Log", "info", listOf(str("ok"))),
                Handler(
                    effect = "Ask",
                    operations = mapOf("name" to OperationClause { _, k -> k.resume(str("Ada")) }),
                ),
            ),
            Handler(
                effect = "Log",
                operations = mapOf("info" to OperationClause { _, k -> k.resume(unit) }),
            ),
        )

        assertDone(unit, evaluator.eval(program))
    }

    @Test
    fun doubleResumeFailsEvenIfFirstResumeCompleted() {
        val program = handle(
            perform("Ask", "name"),
            Handler(
                effect = "Ask",
                operations = mapOf(
                    "name" to OperationClause { _, k ->
                        k.resume(str("Ada"))
                        k.resume(str("Grace"))
                    },
                ),
            ),
        )

        assertEquals(EvalError.ContinuationAlreadyResumed, assertFailed(evaluator.eval(program)))
    }

    @Test
    fun leakedUnusedResumptionCannotResumeAfterScopeExit() {
        var leaked: Resumption? = null
        val program = handle(
            perform("Ask", "name"),
            Handler(
                effect = "Ask",
                operations = mapOf(
                    "name" to OperationClause { _, k ->
                        leaked = k
                        EvalResult.Done(unit)
                    },
                ),
            ),
        )

        assertDone(unit, evaluator.eval(program))
        assertEquals(EvalError.ContinuationEscaped, assertFailed(leaked!!.resume(str("Ada"))))
    }

    @Test
    fun bindContinuationReceivesOperationResult() {
        val program = handle(
            bind(perform("Ask", "age")) { age ->
                ret(int((age as Value.IntValue).value + 1))
            },
            Handler(
                effect = "Ask",
                operations = mapOf("age" to OperationClause { _, k -> k.resume(int(41)) }),
            ),
        )

        assertDone(int(42), evaluator.eval(program))
    }

    @Test
    fun deepHandlerHandlesOperationsAfterResume() {
        var next = 0L
        val program = handle(
            bind(perform("Ask", "next")) { first ->
                bind(perform("Ask", "next")) { second ->
                    ret(Value.PairValue(first, second))
                }
            },
            Handler(
                effect = "Ask",
                operations = mapOf(
                    "next" to OperationClause { _, k ->
                        next += 1
                        k.resume(int(next))
                    },
                ),
            ),
        )

        assertDone(Value.PairValue(int(1), int(2)), evaluator.eval(program))
    }

    @Test
    fun handlerScopeClosesAfterNormalReturn() {
        var leaked: Resumption? = null
        val program = handle(
            bind(perform("Ask", "capture")) {
                ret(str("done"))
            },
            Handler(
                effect = "Ask",
                operations = mapOf(
                    "capture" to OperationClause { _, k ->
                        leaked = k
                        k.resume(unit)
                    },
                ),
            ),
        )

        assertDone(str("done"), evaluator.eval(program))
        assertEquals(EvalError.ContinuationAlreadyResumed, assertFailed(leaked!!.resume(unit)))
    }

    @Test
    fun handlerCanReturnWithoutResuming() {
        val program = handle(
            perform("Abort", "stop"),
            Handler(
                effect = "Abort",
                operations = mapOf(
                    "stop" to OperationClause { _, _ -> EvalResult.Done(str("aborted")) },
                ),
            ),
        )

        assertDone(str("aborted"), evaluator.eval(program))
    }

    private fun assertDone(expected: Value, result: EvalResult) {
        val done = assertIs<EvalResult.Done>(result)
        assertEquals(expected, done.value)
    }

    private fun assertFailed(result: EvalResult): EvalError {
        val failed = assertIs<EvalResult.Failed>(result)
        return failed.error
    }
}
