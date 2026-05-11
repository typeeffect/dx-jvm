package dx.jvm

import dx.cbpv.CoreSourceSpan
import dx.cbpv.ResumeUsage
import dx.cbpv.SelectiveLoweringClass
import dx.cbpv.SelectiveLoweringReason
import dx.cbpv.TypedComputation
import dx.cbpv.TypedHandler
import dx.cbpv.TypedHandlerClause
import dx.cbpv.TypedSourceMapBuilder
import dx.cbpv.TypedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CbpvContinuationBackendTest {
    private val backend = CbpvContinuationBackend()

    @Test
    fun directProgramBelongsToPureBackend() {
        val result = backend.lower(TypedComputation.Return(TypedValue.StringValue("direct")))

        assertEquals(null, result.program)
        assertEquals(
            listOf(CbpvContinuationDiagnostic.DirectPlanBelongsToPureBackend),
            result.diagnostics,
        )
    }

    @Test
    fun lowersTailResumeHandlerFrameWithoutContinuationState() {
        val handle = handleAsk(
            body = TypedComputation.Perform("Ask", "name"),
            nameClause = TypedComputation.Resume(TypedValue.StringValue("Ada")),
        )

        val result = backend.lower(handle)

        assertTrue(result.isSuccess, "${result.diagnostics}")
        val program = assertNotNull(result.program)
        assertEquals(SelectiveLoweringClass.DirectWithHandlerFrame, program.loweringClass)
        assertEquals(listOf("Ask"), program.handlerFrames.map { it.effect })
        assertTrue(program.continuationStates.isEmpty(), "${program.continuationStates}")
    }

    @Test
    fun lowersNonTailResumeToOneShotContinuationState() {
        val handle = handleAsk(
            body = TypedComputation.Perform("Ask", "name"),
            nameClause = TypedComputation.Bind(
                name = "ignored",
                first = TypedComputation.Resume(TypedValue.StringValue("Ada")),
                next = TypedComputation.Return(TypedValue.StringValue("after")),
            ),
        )

        val result = backend.lower(handle)

        assertTrue(result.isSuccess, "${result.diagnostics}")
        val program = assertNotNull(result.program)
        assertEquals(SelectiveLoweringClass.OneShotCapture, program.loweringClass)
        val state = program.continuationStates.single {
            it.reason is SelectiveLoweringReason.ResumeInHandlerClause
        }
        val reason = assertIs<SelectiveLoweringReason.ResumeInHandlerClause>(state.reason)
        assertEquals(ResumeUsage.NonTail, reason.usage)
    }

    @Test
    fun preservesSourceSpansOnContinuationNodes() {
        val perform = TypedComputation.Perform("Ask", "name")
        val handle = handleAsk(
            body = perform,
            nameClause = TypedComputation.Resume(TypedValue.StringValue("Ada")),
        )
        val span = CoreSourceSpan("handler.dx", startOffset = 0, endOffset = 10, line = 1, column = 1)
        val performSpan = CoreSourceSpan("handler.dx", startOffset = 14, endOffset = 23, line = 2, column = 3)
        val sourceMap = TypedSourceMapBuilder()
            .also {
                it.put(handle, span)
                it.put(perform, performSpan)
            }
            .build()

        val result = backend.lower(handle, sourceMap)

        val program = assertNotNull(result.program)
        assertEquals(span, program.handlerFrames.single().source)
        assertTrue(
            program.plan.reasons.any {
                it is SelectiveLoweringReason.OperationHandledByFrame &&
                    it.source == performSpan
            },
            "${program.plan.reasons}",
        )
    }

    @Test
    fun rejectsAsyncUntilRuntimeExists() {
        val result = backend.lower(TypedComputation.Perform("Async", "awaitInt"))

        assertEquals(null, result.program)
        val diagnostic = assertIs<CbpvContinuationDiagnostic.AsyncSuspendRequiresRuntime>(
            result.diagnostics.single(),
        )
        assertTrue(
            diagnostic.reasons.any {
                it is SelectiveLoweringReason.AsyncOperation &&
                    it.effect == "Async" &&
                    it.operation == "awaitInt"
            },
            "${diagnostic.reasons}",
        )
    }

    private fun handleAsk(
        body: TypedComputation,
        nameClause: TypedComputation,
    ): TypedComputation =
        TypedComputation.Handle(
            body = body,
            handler = TypedHandler(
                effect = "Ask",
                clauses = mapOf(
                    "name" to TypedHandlerClause(emptyList(), nameClause),
                ),
            ),
        )
}
