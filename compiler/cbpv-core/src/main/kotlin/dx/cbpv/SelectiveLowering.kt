package dx.cbpv

enum class SelectiveLoweringClass {
    Direct,
    DirectWithHandlerFrame,
    OneShotCapture,
    AsyncSuspend,
}

data class SelectiveLoweringConfig(
    val asyncEffects: Set<EffectName> = setOf("Async"),
)

sealed interface SelectiveLoweringReason {
    val source: CoreSourceSpan?

    data class HandlerFrame(
        val effect: EffectName,
        override val source: CoreSourceSpan?,
    ) : SelectiveLoweringReason

    data class OperationHandledByFrame(
        val effect: EffectName,
        val operation: OperationName,
        override val source: CoreSourceSpan?,
    ) : SelectiveLoweringReason

    data class OperationRequiresCapture(
        val effect: EffectName,
        val operation: OperationName,
        override val source: CoreSourceSpan?,
    ) : SelectiveLoweringReason

    data class AsyncOperation(
        val effect: EffectName,
        val operation: OperationName,
        override val source: CoreSourceSpan?,
    ) : SelectiveLoweringReason

    data class ResumeInHandlerClause(
        val usage: ResumeUsage,
        override val source: CoreSourceSpan?,
    ) : SelectiveLoweringReason
}

enum class ResumeUsage {
    None,
    Tail,
    NonTail,
    Multiple,
}

sealed interface SelectiveContinuationIr {
    val source: CoreSourceSpan?

    data class DirectBlock(
        val id: Int,
        override val source: CoreSourceSpan?,
    ) : SelectiveContinuationIr

    data class HandlerFrame(
        val id: Int,
        val effect: EffectName,
        override val source: CoreSourceSpan?,
    ) : SelectiveContinuationIr

    data class ContinuationState(
        val id: Int,
        val reason: SelectiveLoweringReason,
        override val source: CoreSourceSpan?,
    ) : SelectiveContinuationIr

    data class AwaitPoint(
        val id: Int,
        val effect: EffectName,
        val operation: OperationName,
        override val source: CoreSourceSpan?,
    ) : SelectiveContinuationIr
}

data class SelectiveLoweringPlan(
    val loweringClass: SelectiveLoweringClass,
    val reasons: List<SelectiveLoweringReason>,
    val ir: List<SelectiveContinuationIr>,
) {
    val requiresContinuationIr: Boolean
        get() = loweringClass == SelectiveLoweringClass.OneShotCapture ||
            loweringClass == SelectiveLoweringClass.AsyncSuspend
}

class SelectiveLoweringAnalyzer(
    private val config: SelectiveLoweringConfig = SelectiveLoweringConfig(),
    private val sourceMap: TypedSourceMap = TypedSourceMap.Empty,
) {
    fun analyze(computation: TypedComputation): SelectiveLoweringPlan {
        val builder = PlanBuilder(sourceMap)
        builder.addIr(SelectiveContinuationIr.DirectBlock(builder.nextId(), sourceMap.spanOf(computation)))
        analyzeComputation(computation, AnalysisContext(), builder)
        return builder.build()
    }

    private fun analyzeComputation(
        computation: TypedComputation,
        context: AnalysisContext,
        builder: PlanBuilder,
    ) {
        when (computation) {
            is TypedComputation.Return -> analyzeValue(computation.value, context, builder)
            is TypedComputation.Bind -> {
                analyzeComputation(computation.first, context, builder)
                analyzeComputation(computation.next, context, builder)
            }
            is TypedComputation.If -> {
                analyzeValue(computation.condition, context, builder)
                analyzeComputation(computation.thenBranch, context, builder)
                analyzeComputation(computation.elseBranch, context, builder)
            }
            is TypedComputation.Force -> analyzeForce(computation, context, builder)
            is TypedComputation.Lambda -> analyzeComputation(computation.body, context, builder)
            is TypedComputation.Apply -> {
                analyzeComputation(computation.function, context, builder)
                analyzeValue(computation.argument, context, builder)
            }
            is TypedComputation.Perform -> analyzePerform(computation, context, builder)
            is TypedComputation.Handle -> analyzeHandle(computation, context, builder)
            is TypedComputation.Resume -> {
                analyzeValue(computation.value, context, builder)
                if (context.inHandlerClause) {
                    builder.raise(
                        SelectiveLoweringClass.DirectWithHandlerFrame,
                        SelectiveLoweringReason.ResumeInHandlerClause(
                            usage = ResumeUsage.Tail,
                            source = sourceMap.spanOf(computation),
                        ),
                    )
                } else {
                    builder.raise(
                        SelectiveLoweringClass.OneShotCapture,
                        SelectiveLoweringReason.ResumeInHandlerClause(
                            usage = ResumeUsage.NonTail,
                            source = sourceMap.spanOf(computation),
                        ),
                    )
                }
            }
        }
    }

    private fun analyzeValue(
        value: TypedValue,
        context: AnalysisContext,
        builder: PlanBuilder,
    ) {
        when (value) {
            TypedValue.UnitValue,
            is TypedValue.BoolValue,
            is TypedValue.IntValue,
            is TypedValue.StringValue,
            is TypedValue.Variable,
            -> Unit

            is TypedValue.PairValue -> {
                analyzeValue(value.first, context, builder)
                analyzeValue(value.second, context, builder)
            }
            is TypedValue.ThunkValue -> Unit
        }
    }

    private fun analyzeForce(
        computation: TypedComputation.Force,
        context: AnalysisContext,
        builder: PlanBuilder,
    ) {
        analyzeValue(computation.thunk, context, builder)
        val thunk = computation.thunk
        if (thunk is TypedValue.ThunkValue) {
            analyzeComputation(thunk.computation, context, builder)
        }
    }

    private fun analyzePerform(
        computation: TypedComputation.Perform,
        context: AnalysisContext,
        builder: PlanBuilder,
    ) {
        computation.arguments.forEach { analyzeValue(it, context, builder) }
        val source = sourceMap.spanOf(computation)
        when {
            computation.effect in config.asyncEffects -> {
                val reason = SelectiveLoweringReason.AsyncOperation(
                    effect = computation.effect,
                    operation = computation.operation,
                    source = source,
                )
                builder.raise(SelectiveLoweringClass.AsyncSuspend, reason)
                builder.addIr(
                    SelectiveContinuationIr.AwaitPoint(
                        id = builder.nextId(),
                        effect = computation.effect,
                        operation = computation.operation,
                        source = source,
                    ),
                )
            }
            computation.effect in context.directHandledEffects -> {
                builder.raise(
                    SelectiveLoweringClass.DirectWithHandlerFrame,
                    SelectiveLoweringReason.OperationHandledByFrame(
                        effect = computation.effect,
                        operation = computation.operation,
                        source = source,
                    ),
                )
            }
            else -> {
                val reason = SelectiveLoweringReason.OperationRequiresCapture(
                    effect = computation.effect,
                    operation = computation.operation,
                    source = source,
                )
                builder.raise(SelectiveLoweringClass.OneShotCapture, reason)
                builder.addIr(
                    SelectiveContinuationIr.ContinuationState(
                        id = builder.nextId(),
                        reason = reason,
                        source = source,
                    ),
                )
            }
        }
    }

    private fun analyzeHandle(
        computation: TypedComputation.Handle,
        context: AnalysisContext,
        builder: PlanBuilder,
    ) {
        val source = sourceMap.spanOf(computation)
        builder.raise(
            SelectiveLoweringClass.DirectWithHandlerFrame,
            SelectiveLoweringReason.HandlerFrame(computation.handler.effect, source),
        )
        builder.addIr(
            SelectiveContinuationIr.HandlerFrame(
                id = builder.nextId(),
                effect = computation.handler.effect,
                source = source,
            ),
        )

        val clauseUsages = computation.handler.clauses.values.map { resumeUsage(it.body) }
        val directHandler = clauseUsages.all { it == ResumeUsage.None || it == ResumeUsage.Tail }
        val bodyContext = if (directHandler) {
            context.copy(directHandledEffects = context.directHandledEffects + computation.handler.effect)
        } else {
            context
        }

        analyzeComputation(computation.body, bodyContext, builder)
        computation.handler.clauses.values.forEach { clause ->
            analyzeComputation(clause.body, context.copy(inHandlerClause = true), builder)
        }

        clauseUsages.forEach { usage ->
            when (usage) {
                ResumeUsage.None -> Unit
                ResumeUsage.Tail ->
                    builder.raise(
                        SelectiveLoweringClass.DirectWithHandlerFrame,
                        SelectiveLoweringReason.ResumeInHandlerClause(usage, source),
                    )
                ResumeUsage.NonTail,
                ResumeUsage.Multiple,
                -> {
                    val reason = SelectiveLoweringReason.ResumeInHandlerClause(usage, source)
                    builder.raise(SelectiveLoweringClass.OneShotCapture, reason)
                    builder.addIr(
                        SelectiveContinuationIr.ContinuationState(
                            id = builder.nextId(),
                            reason = reason,
                            source = source,
                        ),
                    )
                }
            }
        }
    }

    private fun resumeUsage(computation: TypedComputation): ResumeUsage =
        when (computation) {
            is TypedComputation.Resume -> ResumeUsage.Tail
            is TypedComputation.Return,
            is TypedComputation.Perform,
            -> ResumeUsage.None
            is TypedComputation.Lambda -> ResumeUsage.None
            is TypedComputation.Force -> resumeUsage(computation.thunk)
            is TypedComputation.Apply -> combineParallel(
                resumeUsage(computation.function),
                resumeUsage(computation.argument),
            )
            is TypedComputation.Bind -> combineSequential(
                resumeUsage(computation.first),
                resumeUsage(computation.next),
            )
            is TypedComputation.If -> combineBranches(
                resumeUsage(computation.thenBranch),
                resumeUsage(computation.elseBranch),
            )
            is TypedComputation.Handle -> combineParallel(
                resumeUsage(computation.body),
                computation.handler.clauses.values.fold(ResumeUsage.None) { acc, clause ->
                    combineParallel(acc, resumeUsage(clause.body))
                },
            )
        }

    private fun resumeUsage(value: TypedValue): ResumeUsage =
        when (value) {
            TypedValue.UnitValue,
            is TypedValue.BoolValue,
            is TypedValue.IntValue,
            is TypedValue.StringValue,
            is TypedValue.Variable,
            -> ResumeUsage.None
            is TypedValue.PairValue -> combineParallel(resumeUsage(value.first), resumeUsage(value.second))
            is TypedValue.ThunkValue -> ResumeUsage.None
        }

    private fun combineSequential(first: ResumeUsage, second: ResumeUsage): ResumeUsage =
        when {
            first == ResumeUsage.None -> second
            second == ResumeUsage.None -> ResumeUsage.NonTail
            first == ResumeUsage.Multiple || second == ResumeUsage.Multiple -> ResumeUsage.Multiple
            else -> ResumeUsage.Multiple
        }

    private fun combineBranches(left: ResumeUsage, right: ResumeUsage): ResumeUsage =
        when {
            left == right -> left
            left == ResumeUsage.Multiple || right == ResumeUsage.Multiple -> ResumeUsage.Multiple
            left == ResumeUsage.NonTail || right == ResumeUsage.NonTail -> ResumeUsage.NonTail
            left == ResumeUsage.None -> right
            right == ResumeUsage.None -> left
            else -> ResumeUsage.Multiple
        }

    private fun combineParallel(left: ResumeUsage, right: ResumeUsage): ResumeUsage =
        when {
            left == ResumeUsage.None -> right
            right == ResumeUsage.None -> left
            left == ResumeUsage.Multiple || right == ResumeUsage.Multiple -> ResumeUsage.Multiple
            else -> ResumeUsage.Multiple
        }
}

private data class AnalysisContext(
    val directHandledEffects: Set<EffectName> = emptySet(),
    val inHandlerClause: Boolean = false,
)

private class PlanBuilder(
    private val sourceMap: TypedSourceMap,
) {
    private var nextId = 0
    private var loweringClass = SelectiveLoweringClass.Direct
    private val reasons = mutableListOf<SelectiveLoweringReason>()
    private val ir = mutableListOf<SelectiveContinuationIr>()

    fun nextId(): Int = nextId++

    fun addIr(node: SelectiveContinuationIr) {
        ir += node
    }

    fun raise(
        candidate: SelectiveLoweringClass,
        reason: SelectiveLoweringReason,
    ) {
        if (candidate.ordinal > loweringClass.ordinal) {
            loweringClass = candidate
        }
        reasons += reason
        when (candidate) {
            SelectiveLoweringClass.Direct,
            SelectiveLoweringClass.DirectWithHandlerFrame,
            -> Unit
            SelectiveLoweringClass.OneShotCapture,
            SelectiveLoweringClass.AsyncSuspend,
            -> Unit
        }
    }

    fun build(): SelectiveLoweringPlan =
        SelectiveLoweringPlan(
            loweringClass = loweringClass,
            reasons = reasons,
            ir = ir,
        )
}
