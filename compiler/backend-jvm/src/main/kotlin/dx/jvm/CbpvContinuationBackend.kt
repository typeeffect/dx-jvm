package dx.jvm

import dx.cbpv.CoreSourceSpan
import dx.cbpv.SelectiveContinuationIr
import dx.cbpv.SelectiveLoweringAnalyzer
import dx.cbpv.SelectiveLoweringClass
import dx.cbpv.SelectiveLoweringPlan
import dx.cbpv.SelectiveLoweringReason
import dx.cbpv.TypedComputation
import dx.cbpv.TypedSourceMap

data class CbpvContinuationLoweringResult(
    val program: CbpvContinuationProgram?,
    val diagnostics: List<CbpvContinuationDiagnostic>,
) {
    val isSuccess: Boolean get() = program != null && diagnostics.isEmpty()
}

data class CbpvContinuationProgram(
    val loweringClass: SelectiveLoweringClass,
    val original: TypedComputation,
    val plan: SelectiveLoweringPlan,
    val nodes: List<CbpvContinuationNode>,
) {
    val directBlocks: List<CbpvContinuationNode.DirectBlock>
        get() = nodes.filterIsInstance<CbpvContinuationNode.DirectBlock>()

    val handlerFrames: List<CbpvContinuationNode.HandlerFrame>
        get() = nodes.filterIsInstance<CbpvContinuationNode.HandlerFrame>()

    val continuationStates: List<CbpvContinuationNode.ContinuationState>
        get() = nodes.filterIsInstance<CbpvContinuationNode.ContinuationState>()
}

sealed interface CbpvContinuationNode {
    val id: Int
    val source: CoreSourceSpan?

    data class DirectBlock(
        override val id: Int,
        override val source: CoreSourceSpan?,
    ) : CbpvContinuationNode

    data class HandlerFrame(
        override val id: Int,
        val effect: String,
        override val source: CoreSourceSpan?,
    ) : CbpvContinuationNode

    data class ContinuationState(
        override val id: Int,
        val reason: SelectiveLoweringReason,
        override val source: CoreSourceSpan?,
    ) : CbpvContinuationNode
}

sealed interface CbpvContinuationDiagnostic {
    data object DirectPlanBelongsToPureBackend : CbpvContinuationDiagnostic
    data class AsyncSuspendRequiresRuntime(
        val reasons: List<SelectiveLoweringReason>,
    ) : CbpvContinuationDiagnostic

    data class UnexpectedAwaitPoint(
        val id: Int,
        val effect: String,
        val operation: String,
        val source: CoreSourceSpan?,
    ) : CbpvContinuationDiagnostic
}

class CbpvContinuationBackend {
    fun lower(
        computation: TypedComputation,
        sourceMap: TypedSourceMap = TypedSourceMap.Empty,
    ): CbpvContinuationLoweringResult {
        val plan = SelectiveLoweringAnalyzer(sourceMap = sourceMap).analyze(computation)

        return when (plan.loweringClass) {
            SelectiveLoweringClass.Direct ->
                CbpvContinuationLoweringResult(
                    program = null,
                    diagnostics = listOf(CbpvContinuationDiagnostic.DirectPlanBelongsToPureBackend),
                )
            SelectiveLoweringClass.AsyncSuspend ->
                CbpvContinuationLoweringResult(
                    program = null,
                    diagnostics = listOf(CbpvContinuationDiagnostic.AsyncSuspendRequiresRuntime(plan.reasons)),
                )
            SelectiveLoweringClass.DirectWithHandlerFrame,
            SelectiveLoweringClass.OneShotCapture,
            -> lowerPlan(computation, plan)
        }
    }

    private fun lowerPlan(
        computation: TypedComputation,
        plan: SelectiveLoweringPlan,
    ): CbpvContinuationLoweringResult {
        val diagnostics = mutableListOf<CbpvContinuationDiagnostic>()
        val nodes = plan.ir.mapNotNull { ir ->
            when (ir) {
                is SelectiveContinuationIr.DirectBlock ->
                    CbpvContinuationNode.DirectBlock(ir.id, ir.source)
                is SelectiveContinuationIr.HandlerFrame ->
                    CbpvContinuationNode.HandlerFrame(ir.id, ir.effect, ir.source)
                is SelectiveContinuationIr.ContinuationState ->
                    CbpvContinuationNode.ContinuationState(ir.id, ir.reason, ir.source)
                is SelectiveContinuationIr.AwaitPoint -> {
                    diagnostics += CbpvContinuationDiagnostic.UnexpectedAwaitPoint(
                        id = ir.id,
                        effect = ir.effect,
                        operation = ir.operation,
                        source = ir.source,
                    )
                    null
                }
            }
        }

        if (diagnostics.isNotEmpty()) {
            return CbpvContinuationLoweringResult(program = null, diagnostics = diagnostics)
        }

        return CbpvContinuationLoweringResult(
            program = CbpvContinuationProgram(
                loweringClass = plan.loweringClass,
                original = computation,
                plan = plan,
                nodes = nodes,
            ),
            diagnostics = emptyList(),
        )
    }
}
