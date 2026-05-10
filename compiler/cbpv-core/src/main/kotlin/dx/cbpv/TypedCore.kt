package dx.cbpv

typealias EffectName = String
typealias OperationName = String

sealed interface ValueType {
    data object UnitType : ValueType
    data object BoolType : ValueType
    data object IntType : ValueType
    data object StringType : ValueType
    data class PairType(val first: ValueType, val second: ValueType) : ValueType
    data class ThunkType(val computation: ComputationType) : ValueType
    data class FunctionType(val parameter: ValueType, val result: ComputationType) : ValueType
}

data class ComputationType(
    val result: ValueType,
    val effects: Set<EffectName> = emptySet(),
)

data class OperationSignature(
    val arguments: List<ValueType>,
    val result: ValueType,
)

data class EffectSignature(
    val name: EffectName,
    val operations: Map<OperationName, OperationSignature>,
)

data class TypeEnvironment(
    val variables: Map<String, ValueType> = emptyMap(),
    val effects: Map<EffectName, EffectSignature> = emptyMap(),
) {
    fun withVariable(name: String, type: ValueType): TypeEnvironment =
        copy(variables = variables + (name to type))

    fun withEffect(signature: EffectSignature): TypeEnvironment =
        copy(effects = effects + (signature.name to signature))
}

sealed interface TypedValue {
    data object UnitValue : TypedValue
    data class BoolValue(val value: Boolean) : TypedValue
    data class IntValue(val value: Long) : TypedValue
    data class StringValue(val value: String) : TypedValue
    data class PairValue(val first: TypedValue, val second: TypedValue) : TypedValue
    data class Variable(val name: String) : TypedValue
    data class ThunkValue(val computation: TypedComputation) : TypedValue
    data class Lambda(
        val parameter: String,
        val parameterType: ValueType,
        val body: TypedComputation,
    ) : TypedValue
}

sealed interface TypedComputation {
    data class Return(val value: TypedValue) : TypedComputation
    data class Bind(
        val name: String,
        val first: TypedComputation,
        val next: TypedComputation,
    ) : TypedComputation

    data class Force(val thunk: TypedValue) : TypedComputation
    data class Apply(val function: TypedValue, val argument: TypedValue) : TypedComputation
    data class Perform(
        val effect: EffectName,
        val operation: OperationName,
        val arguments: List<TypedValue> = emptyList(),
    ) : TypedComputation

    data class Handle(
        val body: TypedComputation,
        val handler: TypedHandler,
    ) : TypedComputation

    data class Resume(val value: TypedValue) : TypedComputation
}

data class TypedHandler(
    val effect: EffectName,
    val clauses: Map<OperationName, TypedHandlerClause>,
)

data class TypedHandlerClause(
    val parameters: List<String>,
    val body: TypedComputation,
)

sealed interface TypeDiagnostic {
    data class UnknownVariable(val name: String) : TypeDiagnostic
    data class UnknownEffect(val effect: EffectName) : TypeDiagnostic
    data class UnknownOperation(
        val effect: EffectName,
        val operation: OperationName,
    ) : TypeDiagnostic

    data class DuplicateHandlerParameter(val name: String) : TypeDiagnostic
    data class MissingHandlerClause(
        val effect: EffectName,
        val operation: OperationName,
    ) : TypeDiagnostic

    data class TypeMismatch(
        val expected: ValueType,
        val actual: ValueType,
    ) : TypeDiagnostic

    data class ArityMismatch(
        val expected: Int,
        val actual: Int,
    ) : TypeDiagnostic

    data object ForceNonThunk : TypeDiagnostic
    data object ApplyNonFunction : TypeDiagnostic
    data object ResumeOutsideHandlerClause : TypeDiagnostic
    data class ResumeTypeMismatch(
        val expected: ValueType,
        val actual: ValueType,
    ) : TypeDiagnostic

    data class UnhandledEffects(
        val effects: Set<EffectName>,
    ) : TypeDiagnostic
}

data class TypeCheckResult(
    val type: ComputationType?,
    val diagnostics: List<TypeDiagnostic>,
) {
    val isSuccess: Boolean get() = diagnostics.isEmpty() && type != null
}

private data class HandlerContext(
    val resumeValue: ValueType,
    val handlerResult: ValueType,
)

class TypeChecker(private val environment: TypeEnvironment) {
    fun infer(computation: TypedComputation): TypeCheckResult {
        val diagnostics = mutableListOf<TypeDiagnostic>()
        val type = inferComputation(computation, environment, handlerContext = null, diagnostics)
        return TypeCheckResult(type, diagnostics)
    }

    fun checkClosed(
        computation: TypedComputation,
        expectedResult: ValueType,
        allowedEffects: Set<EffectName> = emptySet(),
    ): TypeCheckResult {
        val result = infer(computation)
        val diagnostics = result.diagnostics.toMutableList()
        val type = result.type

        if (type != null) {
            if (type.result != expectedResult) {
                diagnostics += TypeDiagnostic.TypeMismatch(expectedResult, type.result)
            }
            val unhandled = type.effects - allowedEffects
            if (unhandled.isNotEmpty()) {
                diagnostics += TypeDiagnostic.UnhandledEffects(unhandled)
            }
        }

        return TypeCheckResult(type, diagnostics)
    }

    private fun inferComputation(
        computation: TypedComputation,
        env: TypeEnvironment,
        handlerContext: HandlerContext?,
        diagnostics: MutableList<TypeDiagnostic>,
    ): ComputationType? =
        when (computation) {
            is TypedComputation.Return -> {
                val type = inferValue(computation.value, env, diagnostics)
                type?.let { ComputationType(it) }
            }
            is TypedComputation.Bind -> {
                val firstType = inferComputation(computation.first, env, handlerContext, diagnostics)
                if (firstType == null) {
                    null
                } else {
                    val nextType = inferComputation(
                        computation.next,
                        env.withVariable(computation.name, firstType.result),
                        handlerContext,
                        diagnostics,
                    )
                    nextType?.let {
                        ComputationType(it.result, firstType.effects + it.effects)
                    }
                }
            }
            is TypedComputation.Force -> {
                when (val thunkType = inferValue(computation.thunk, env, diagnostics)) {
                    is ValueType.ThunkType -> thunkType.computation
                    null -> null
                    else -> {
                        diagnostics += TypeDiagnostic.ForceNonThunk
                        null
                    }
                }
            }
            is TypedComputation.Apply -> {
                val functionType = inferValue(computation.function, env, diagnostics)
                val argumentType = inferValue(computation.argument, env, diagnostics)
                when (functionType) {
                    is ValueType.FunctionType -> {
                        if (argumentType != null && argumentType != functionType.parameter) {
                            diagnostics += TypeDiagnostic.TypeMismatch(functionType.parameter, argumentType)
                        }
                        functionType.result
                    }
                    null -> null
                    else -> {
                        diagnostics += TypeDiagnostic.ApplyNonFunction
                        null
                    }
                }
            }
            is TypedComputation.Perform -> inferPerform(computation, env, diagnostics)
            is TypedComputation.Handle -> inferHandle(computation, env, handlerContext, diagnostics)
            is TypedComputation.Resume -> {
                if (handlerContext == null) {
                    diagnostics += TypeDiagnostic.ResumeOutsideHandlerClause
                    null
                } else {
                    val valueType = inferValue(computation.value, env, diagnostics)
                    if (valueType != null && valueType != handlerContext.resumeValue) {
                        diagnostics += TypeDiagnostic.ResumeTypeMismatch(handlerContext.resumeValue, valueType)
                    }
                    ComputationType(handlerContext.handlerResult)
                }
            }
        }

    private fun inferPerform(
        computation: TypedComputation.Perform,
        env: TypeEnvironment,
        diagnostics: MutableList<TypeDiagnostic>,
    ): ComputationType? {
        val effect = env.effects[computation.effect]
        if (effect == null) {
            diagnostics += TypeDiagnostic.UnknownEffect(computation.effect)
            return null
        }

        val operation = effect.operations[computation.operation]
        if (operation == null) {
            diagnostics += TypeDiagnostic.UnknownOperation(computation.effect, computation.operation)
            return null
        }

        if (operation.arguments.size != computation.arguments.size) {
            diagnostics += TypeDiagnostic.ArityMismatch(operation.arguments.size, computation.arguments.size)
            return null
        }

        operation.arguments.zip(computation.arguments).forEach { (expected, value) ->
            val actual = inferValue(value, env, diagnostics)
            if (actual != null && actual != expected) {
                diagnostics += TypeDiagnostic.TypeMismatch(expected, actual)
            }
        }

        return ComputationType(operation.result, setOf(computation.effect))
    }

    private fun inferHandle(
        computation: TypedComputation.Handle,
        env: TypeEnvironment,
        outerHandlerContext: HandlerContext?,
        diagnostics: MutableList<TypeDiagnostic>,
    ): ComputationType? {
        val signature = env.effects[computation.handler.effect]
        if (signature == null) {
            diagnostics += TypeDiagnostic.UnknownEffect(computation.handler.effect)
            return null
        }

        val bodyType = inferComputation(computation.body, env, outerHandlerContext, diagnostics)
            ?: return null

        for (operationName in signature.operations.keys) {
            if (operationName !in computation.handler.clauses) {
                diagnostics += TypeDiagnostic.MissingHandlerClause(signature.name, operationName)
            }
        }

        var clauseEffects = emptySet<EffectName>()
        for ((operationName, clause) in computation.handler.clauses) {
            val operation = signature.operations[operationName]
            if (operation == null) {
                diagnostics += TypeDiagnostic.UnknownOperation(signature.name, operationName)
                continue
            }
            if (operation.arguments.size != clause.parameters.size) {
                diagnostics += TypeDiagnostic.ArityMismatch(operation.arguments.size, clause.parameters.size)
                continue
            }

            val duplicate = clause.parameters.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
            if (duplicate != null) {
                diagnostics += TypeDiagnostic.DuplicateHandlerParameter(duplicate.key)
                continue
            }

            val clauseEnv = operation.arguments.zip(clause.parameters).fold(env) { acc, (type, name) ->
                acc.withVariable(name, type)
            }
            val clauseType = inferComputation(
                clause.body,
                clauseEnv,
                HandlerContext(operation.result, bodyType.result),
                diagnostics,
            )
            if (clauseType != null) {
                if (clauseType.result != bodyType.result) {
                    diagnostics += TypeDiagnostic.TypeMismatch(bodyType.result, clauseType.result)
                }
                clauseEffects = clauseEffects + clauseType.effects
            }
        }

        return ComputationType(
            result = bodyType.result,
            effects = (bodyType.effects - signature.name) + clauseEffects,
        )
    }

    private fun inferValue(
        value: TypedValue,
        env: TypeEnvironment,
        diagnostics: MutableList<TypeDiagnostic>,
    ): ValueType? =
        when (value) {
            TypedValue.UnitValue -> ValueType.UnitType
            is TypedValue.BoolValue -> ValueType.BoolType
            is TypedValue.IntValue -> ValueType.IntType
            is TypedValue.StringValue -> ValueType.StringType
            is TypedValue.PairValue -> {
                val first = inferValue(value.first, env, diagnostics)
                val second = inferValue(value.second, env, diagnostics)
                if (first != null && second != null) {
                    ValueType.PairType(first, second)
                } else {
                    null
                }
            }
            is TypedValue.Variable -> {
                env.variables[value.name] ?: run {
                    diagnostics += TypeDiagnostic.UnknownVariable(value.name)
                    null
                }
            }
            is TypedValue.ThunkValue -> {
                val computationType = inferComputation(value.computation, env, handlerContext = null, diagnostics)
                computationType?.let { ValueType.ThunkType(it) }
            }
            is TypedValue.Lambda -> {
                val bodyType = inferComputation(
                    value.body,
                    env.withVariable(value.parameter, value.parameterType),
                    handlerContext = null,
                    diagnostics,
                )
                bodyType?.let { ValueType.FunctionType(value.parameterType, it) }
            }
        }
}
