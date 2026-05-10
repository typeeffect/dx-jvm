package dx.cbpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectiveLoweringAnalyzerTest {
    @Test
    fun pureComputationStaysDirect() {
        val plan = SelectiveLoweringAnalyzer().analyze(
            TypedComputation.Bind(
                name = "x",
                first = TypedComputation.Return(TypedValue.IntValue(1)),
                next = TypedComputation.Return(TypedValue.Variable("x")),
            ),
        )

        assertEquals(SelectiveLoweringClass.Direct, plan.loweringClass)
        assertFalse(plan.requiresContinuationIr)
        assertTrue(plan.reasons.isEmpty(), "${plan.reasons}")
        assertTrue(plan.ir.single() is SelectiveContinuationIr.DirectBlock, "${plan.ir}")
    }

    @Test
    fun tailResumeHandlerUsesDirectHandlerFrame() {
        val plan = SelectiveLoweringAnalyzer().analyze(
            TypedComputation.Handle(
                body = TypedComputation.Perform("Ask", "name"),
                handler = TypedHandler(
                    effect = "Ask",
                    clauses = mapOf(
                        "name" to TypedHandlerClause(emptyList(), TypedComputation.Resume(TypedValue.StringValue("Ada"))),
                    ),
                ),
            ),
        )

        assertEquals(SelectiveLoweringClass.DirectWithHandlerFrame, plan.loweringClass)
        assertFalse(plan.requiresContinuationIr)
        assertTrue(plan.ir.any { it is SelectiveContinuationIr.HandlerFrame }, "${plan.ir}")
        assertFalse(plan.ir.any { it is SelectiveContinuationIr.ContinuationState }, "${plan.ir}")
        assertTrue(
            plan.reasons.any {
                it is SelectiveLoweringReason.OperationHandledByFrame &&
                    it.effect == "Ask" &&
                    it.operation == "name"
            },
            "${plan.reasons}",
        )
    }

    @Test
    fun nonTailResumeRequiresOneShotCapture() {
        val plan = SelectiveLoweringAnalyzer().analyze(
            TypedComputation.Handle(
                body = TypedComputation.Perform("Ask", "name"),
                handler = TypedHandler(
                    effect = "Ask",
                    clauses = mapOf(
                        "name" to TypedHandlerClause(
                            emptyList(),
                            TypedComputation.Bind(
                                name = "ignored",
                                first = TypedComputation.Resume(TypedValue.StringValue("Ada")),
                                next = TypedComputation.Return(TypedValue.StringValue("after")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(SelectiveLoweringClass.OneShotCapture, plan.loweringClass)
        assertTrue(plan.requiresContinuationIr)
        val state = assertIs<SelectiveContinuationIr.ContinuationState>(
            plan.ir.last { it is SelectiveContinuationIr.ContinuationState },
        )
        val reason = assertIs<SelectiveLoweringReason.ResumeInHandlerClause>(state.reason)
        assertEquals(ResumeUsage.NonTail, reason.usage)
    }

    @Test
    fun unhandledOperationRequiresOneShotCapture() {
        val plan = SelectiveLoweringAnalyzer().analyze(TypedComputation.Perform("Ask", "name"))

        assertEquals(SelectiveLoweringClass.OneShotCapture, plan.loweringClass)
        assertTrue(plan.requiresContinuationIr)
        assertTrue(
            plan.reasons.any {
                it is SelectiveLoweringReason.OperationRequiresCapture &&
                    it.effect == "Ask" &&
                    it.operation == "name"
            },
            "${plan.reasons}",
        )
    }

    @Test
    fun asyncOperationRequiresAsyncSuspendAndAwaitPoint() {
        val perform = TypedComputation.Perform("Async", "awaitInt")
        val span = CoreSourceSpan("async.dx", startOffset = 4, endOffset = 12, line = 1, column = 5)
        val sourceMap = TypedSourceMapBuilder()
            .also { it.put(perform, span) }
            .build()

        val plan = SelectiveLoweringAnalyzer(sourceMap = sourceMap).analyze(perform)

        assertEquals(SelectiveLoweringClass.AsyncSuspend, plan.loweringClass)
        assertTrue(plan.requiresContinuationIr)
        val await = assertIs<SelectiveContinuationIr.AwaitPoint>(
            plan.ir.last { it is SelectiveContinuationIr.AwaitPoint },
        )
        assertEquals("Async", await.effect)
        assertEquals("awaitInt", await.operation)
        assertEquals(span, await.source)
    }

    @Test
    fun asyncDominatesHandlerFrame() {
        val plan = SelectiveLoweringAnalyzer().analyze(
            TypedComputation.Handle(
                body = TypedComputation.Perform("Async", "awaitInt"),
                handler = TypedHandler(
                    effect = "Ask",
                    clauses = mapOf(
                        "name" to TypedHandlerClause(emptyList(), TypedComputation.Resume(TypedValue.StringValue("Ada"))),
                    ),
                ),
            ),
        )

        assertEquals(SelectiveLoweringClass.AsyncSuspend, plan.loweringClass)
        assertTrue(plan.ir.any { it is SelectiveContinuationIr.HandlerFrame }, "${plan.ir}")
        assertTrue(plan.ir.any { it is SelectiveContinuationIr.AwaitPoint }, "${plan.ir}")
    }
}
