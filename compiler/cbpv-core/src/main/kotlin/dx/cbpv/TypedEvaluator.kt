package dx.cbpv

import java.util.concurrent.atomic.AtomicReference

sealed interface RuntimeValue {
    data object UnitValue : RuntimeValue
    data class BoolValue(val value: Boolean) : RuntimeValue
    data class IntValue(val value: Long) : RuntimeValue
    data class StringValue(val value: String) : RuntimeValue
    data class PairValue(val first: RuntimeValue, val second: RuntimeValue) : RuntimeValue
    data class ThunkValue(
        val computation: TypedComputation,
        val environment: RuntimeEnvironment,
    ) : RuntimeValue

    data class ClosureValue(
        val parameter: String,
        val body: TypedComputation,
        val environment: RuntimeEnvironment,
    ) : RuntimeValue
}

data class RuntimeEnvironment(
    val variables: Map<String, RuntimeValue> = emptyMap(),
) {
    fun withVariable(name: String, value: RuntimeValue): RuntimeEnvironment =
        copy(variables = variables + (name to value))
}

sealed interface RuntimeResult {
    data class Done(val value: RuntimeValue) : RuntimeResult
    data class Failed(val error: RuntimeError) : RuntimeResult
}

sealed interface RuntimeError {
    data class UnknownVariable(val name: String) : RuntimeError
    data class TypeMismatch(val expected: String, val actual: RuntimeValue) : RuntimeError
    data class UnhandledEffect(val effect: EffectName, val operation: OperationName) : RuntimeError
    data class MissingOperation(val effect: EffectName, val operation: OperationName) : RuntimeError
    data object ResumeOutsideHandlerClause : RuntimeError
    data object ContinuationAlreadyResumed : RuntimeError
    data object ContinuationEscaped : RuntimeError
}

private enum class RuntimeScopeState {
    Active,
    Closed,
}

private class RuntimeHandlerScope {
    private val state = AtomicReference(RuntimeScopeState.Active)

    fun close() {
        state.compareAndSet(RuntimeScopeState.Active, RuntimeScopeState.Closed)
    }

    fun isActive(): Boolean = state.get() == RuntimeScopeState.Active
}

private enum class RuntimeResumptionState {
    Fresh,
    Resumed,
}

private class RuntimeResumption(
    private val scope: RuntimeHandlerScope,
    private val continueWith: (RuntimeValue) -> RuntimeResult,
) {
    private val state = AtomicReference(RuntimeResumptionState.Fresh)

    fun resume(value: RuntimeValue): RuntimeResult {
        if (!state.compareAndSet(RuntimeResumptionState.Fresh, RuntimeResumptionState.Resumed)) {
            return RuntimeResult.Failed(RuntimeError.ContinuationAlreadyResumed)
        }
        if (!scope.isActive()) {
            return RuntimeResult.Failed(RuntimeError.ContinuationEscaped)
        }
        return continueWith(value)
    }
}

private data class RuntimeHandlerFrame(
    val handler: TypedHandler,
    val environment: RuntimeEnvironment,
    val scope: RuntimeHandlerScope,
)

private data class RuntimeHandlerContext(
    val resumption: RuntimeResumption,
)

class TypedEvaluator {
    fun eval(computation: TypedComputation): RuntimeResult =
        eval(
            computation = computation,
            environment = RuntimeEnvironment(),
            handlers = emptyList(),
            handlerContext = null,
        ) { value -> RuntimeResult.Done(value) }

    private fun eval(
        computation: TypedComputation,
        environment: RuntimeEnvironment,
        handlers: List<RuntimeHandlerFrame>,
        handlerContext: RuntimeHandlerContext?,
        continuation: (RuntimeValue) -> RuntimeResult,
    ): RuntimeResult =
        when (computation) {
            is TypedComputation.Return ->
                when (val value = evalValue(computation.value, environment)) {
                    is RuntimeValueResult.Done -> continuation(value.value)
                    is RuntimeValueResult.Failed -> RuntimeResult.Failed(value.error)
                }
            is TypedComputation.Bind ->
                eval(computation.first, environment, handlers, handlerContext) { value ->
                    eval(
                        computation.next,
                        environment.withVariable(computation.name, value),
                        handlers,
                        handlerContext,
                        continuation,
                    )
                }
            is TypedComputation.If ->
                when (val condition = evalValue(computation.condition, environment)) {
                    is RuntimeValueResult.Done ->
                        when (val value = condition.value) {
                            is RuntimeValue.BoolValue ->
                                eval(
                                    if (value.value) computation.thenBranch else computation.elseBranch,
                                    environment,
                                    handlers,
                                    handlerContext,
                                    continuation,
                                )
                            else -> RuntimeResult.Failed(RuntimeError.TypeMismatch("bool", value))
                        }
                    is RuntimeValueResult.Failed -> RuntimeResult.Failed(condition.error)
                }
            is TypedComputation.Force ->
                when (val thunk = evalValue(computation.thunk, environment)) {
                    is RuntimeValueResult.Done ->
                        when (val value = thunk.value) {
                            is RuntimeValue.ThunkValue ->
                                eval(value.computation, value.environment, handlers, handlerContext, continuation)
                            else -> RuntimeResult.Failed(RuntimeError.TypeMismatch("thunk", value))
                        }
                    is RuntimeValueResult.Failed -> RuntimeResult.Failed(thunk.error)
                }
            is TypedComputation.Apply ->
                apply(computation, environment, handlers, handlerContext, continuation)
            is TypedComputation.Perform ->
                perform(computation, environment, handlers, continuation)
            is TypedComputation.Handle ->
                handle(computation, environment, handlers, handlerContext, continuation)
            is TypedComputation.Resume ->
                resume(computation, environment, handlerContext, continuation)
        }

    private fun apply(
        computation: TypedComputation.Apply,
        environment: RuntimeEnvironment,
        handlers: List<RuntimeHandlerFrame>,
        handlerContext: RuntimeHandlerContext?,
        continuation: (RuntimeValue) -> RuntimeResult,
    ): RuntimeResult {
        val function = evalValue(computation.function, environment)
        val argument = evalValue(computation.argument, environment)
        if (function is RuntimeValueResult.Failed) {
            return RuntimeResult.Failed(function.error)
        }
        if (argument is RuntimeValueResult.Failed) {
            return RuntimeResult.Failed(argument.error)
        }
        val functionValue = (function as RuntimeValueResult.Done).value
        val argumentValue = (argument as RuntimeValueResult.Done).value
        return when (functionValue) {
            is RuntimeValue.ClosureValue ->
                eval(
                    functionValue.body,
                    functionValue.environment.withVariable(functionValue.parameter, argumentValue),
                    handlers,
                    handlerContext,
                    continuation,
                )
            else -> RuntimeResult.Failed(RuntimeError.TypeMismatch("function", functionValue))
        }
    }

    private fun perform(
        computation: TypedComputation.Perform,
        environment: RuntimeEnvironment,
        handlers: List<RuntimeHandlerFrame>,
        continuation: (RuntimeValue) -> RuntimeResult,
    ): RuntimeResult {
        val arguments = computation.arguments.map { argument ->
            when (val value = evalValue(argument, environment)) {
                is RuntimeValueResult.Done -> value.value
                is RuntimeValueResult.Failed -> return RuntimeResult.Failed(value.error)
            }
        }

        val indexedFrame = handlers.withIndex().reversed().firstOrNull { (_, frame) ->
            frame.handler.effect == computation.effect
        } ?: return RuntimeResult.Failed(
            RuntimeError.UnhandledEffect(computation.effect, computation.operation),
        )

        val frame = indexedFrame.value
        val clause = frame.handler.clauses[computation.operation]
            ?: return RuntimeResult.Failed(
                RuntimeError.MissingOperation(computation.effect, computation.operation),
            )

        val clauseEnvironment = clause.parameters.zip(arguments).fold(frame.environment) { acc, (name, value) ->
            acc.withVariable(name, value)
        }
        val outerHandlers = handlers.take(indexedFrame.index) + handlers.drop(indexedFrame.index + 1)
        val resumption = RuntimeResumption(frame.scope, continuation)
        return eval(
            computation = clause.body,
            environment = clauseEnvironment,
            handlers = outerHandlers,
            handlerContext = RuntimeHandlerContext(resumption),
        ) { value -> RuntimeResult.Done(value) }
    }

    private fun handle(
        computation: TypedComputation.Handle,
        environment: RuntimeEnvironment,
        handlers: List<RuntimeHandlerFrame>,
        handlerContext: RuntimeHandlerContext?,
        continuation: (RuntimeValue) -> RuntimeResult,
    ): RuntimeResult {
        val scope = RuntimeHandlerScope()
        val frame = RuntimeHandlerFrame(computation.handler, environment, scope)
        var closed = false

        fun closeScope() {
            if (!closed) {
                closed = true
                scope.close()
            }
        }

        val result = eval(computation.body, environment, handlers + frame, handlerContext) { value ->
            closeScope()
            continuation(value)
        }
        closeScope()
        return result
    }

    private fun resume(
        computation: TypedComputation.Resume,
        environment: RuntimeEnvironment,
        handlerContext: RuntimeHandlerContext?,
        continuation: (RuntimeValue) -> RuntimeResult,
    ): RuntimeResult {
        if (handlerContext == null) {
            return RuntimeResult.Failed(RuntimeError.ResumeOutsideHandlerClause)
        }
        return when (val value = evalValue(computation.value, environment)) {
            is RuntimeValueResult.Done ->
                when (val resumed = handlerContext.resumption.resume(value.value)) {
                    is RuntimeResult.Done -> continuation(resumed.value)
                    is RuntimeResult.Failed -> resumed
                }
            is RuntimeValueResult.Failed -> RuntimeResult.Failed(value.error)
        }
    }

    private fun evalValue(
        value: TypedValue,
        environment: RuntimeEnvironment,
    ): RuntimeValueResult =
        when (value) {
            TypedValue.UnitValue -> RuntimeValueResult.Done(RuntimeValue.UnitValue)
            is TypedValue.BoolValue -> RuntimeValueResult.Done(RuntimeValue.BoolValue(value.value))
            is TypedValue.IntValue -> RuntimeValueResult.Done(RuntimeValue.IntValue(value.value))
            is TypedValue.StringValue -> RuntimeValueResult.Done(RuntimeValue.StringValue(value.value))
            is TypedValue.PairValue -> {
                val first = evalValue(value.first, environment)
                val second = evalValue(value.second, environment)
                when {
                    first is RuntimeValueResult.Failed -> first
                    second is RuntimeValueResult.Failed -> second
                    else -> RuntimeValueResult.Done(
                        RuntimeValue.PairValue(
                            (first as RuntimeValueResult.Done).value,
                            (second as RuntimeValueResult.Done).value,
                        ),
                    )
                }
            }
            is TypedValue.Variable ->
                environment.variables[value.name]?.let(RuntimeValueResult::Done)
                    ?: RuntimeValueResult.Failed(RuntimeError.UnknownVariable(value.name))
            is TypedValue.ThunkValue ->
                RuntimeValueResult.Done(RuntimeValue.ThunkValue(value.computation, environment))
            is TypedValue.Lambda ->
                RuntimeValueResult.Done(RuntimeValue.ClosureValue(value.parameter, value.body, environment))
        }
}

private sealed interface RuntimeValueResult {
    data class Done(val value: RuntimeValue) : RuntimeValueResult
    data class Failed(val error: RuntimeError) : RuntimeValueResult
}
