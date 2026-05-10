package dx.frontend

import dx.cbpv.TypedComputation
import dx.cbpv.TypedValue

sealed interface LowerDiagnostic {
    data class UnsupportedExpression(val expression: String, val span: SourceSpan) : LowerDiagnostic
}

data class LowerResult(
    val computation: TypedComputation?,
    val diagnostics: List<LowerDiagnostic>,
)

class CbpvLowerer {
    fun lower(module: DxModule): LowerResult {
        val diagnostics = mutableListOf<LowerDiagnostic>()
        val computation = lowerComputation(module.expression, diagnostics)
        return LowerResult(computation, diagnostics)
    }

    private fun lowerComputation(
        expr: DxExpr,
        diagnostics: MutableList<LowerDiagnostic>,
    ): TypedComputation? =
        when (expr) {
            is DxExpr.Val -> {
                val value = lowerComputation(expr.value, diagnostics)
                val body = lowerComputation(expr.body, diagnostics)
                if (value != null && body != null) {
                    TypedComputation.Bind(expr.name, value, body)
                } else {
                    null
                }
            }
            is DxExpr.If -> {
                val condition = lowerValue(expr.condition, diagnostics)
                val thenBranch = lowerComputation(expr.thenBranch, diagnostics)
                val elseBranch = lowerComputation(expr.elseBranch, diagnostics)
                if (condition != null && thenBranch != null && elseBranch != null) {
                    TypedComputation.If(condition, thenBranch, elseBranch)
                } else {
                    null
                }
            }
            is DxExpr.Force -> {
                val value = lowerValue(expr.value, diagnostics)
                value?.let(TypedComputation::Force)
            }
            is DxExpr.Apply -> {
                val function = lowerValue(expr.function, diagnostics)
                val argument = lowerValue(expr.argument, diagnostics)
                if (function != null && argument != null) {
                    TypedComputation.Apply(function, argument)
                } else {
                    null
                }
            }
            else -> {
                val value = lowerValue(expr, diagnostics)
                value?.let(TypedComputation::Return)
            }
        }

    private fun lowerValue(
        expr: DxExpr,
        diagnostics: MutableList<LowerDiagnostic>,
    ): TypedValue? =
        when (expr) {
            is DxExpr.UnitLiteral -> TypedValue.UnitValue
            is DxExpr.BoolLiteral -> TypedValue.BoolValue(expr.value)
            is DxExpr.IntLiteral -> TypedValue.IntValue(expr.value)
            is DxExpr.StringLiteral -> TypedValue.StringValue(expr.value)
            is DxExpr.Variable -> TypedValue.Variable(expr.name)
            is DxExpr.PairExpr -> {
                val first = lowerValue(expr.first, diagnostics)
                val second = lowerValue(expr.second, diagnostics)
                if (first != null && second != null) {
                    TypedValue.PairValue(first, second)
                } else {
                    null
                }
            }
            is DxExpr.Thunk -> {
                val body = lowerComputation(expr.body, diagnostics)
                body?.let(TypedValue::ThunkValue)
            }
            is DxExpr.Lambda -> {
                val body = lowerComputation(expr.body, diagnostics)
                body?.let {
                    TypedValue.Lambda(expr.parameter, expr.parameterType, it)
                }
            }
            is DxExpr.Apply,
            is DxExpr.Force,
            is DxExpr.If,
            is DxExpr.Val -> {
                diagnostics += LowerDiagnostic.UnsupportedExpression(
                    "effectful expression is not a value in the initial frontend subset",
                    expr.span,
                )
                null
            }
            else -> {
                diagnostics += LowerDiagnostic.UnsupportedExpression(
                    "effectful expression is not a value in the initial frontend subset",
                    expr.span,
                )
                null
            }
        }
}

data class FrontendResult(
    val module: DxModule?,
    val computation: TypedComputation?,
    val lexDiagnostics: List<LexDiagnostic>,
    val parseDiagnostics: List<ParseDiagnostic>,
    val lowerDiagnostics: List<LowerDiagnostic>,
) {
    val isSuccess: Boolean get() =
        module != null &&
            computation != null &&
            lexDiagnostics.isEmpty() &&
            parseDiagnostics.isEmpty() &&
            lowerDiagnostics.isEmpty()
}

class FrontendPipeline {
    fun compile(source: SourceId, text: String): FrontendResult {
        val lexed = Lexer(source, text).lex()
        if (lexed.diagnostics.isNotEmpty()) {
            return FrontendResult(null, null, lexed.diagnostics, emptyList(), emptyList())
        }

        val parsed = Parser(lexed.tokens).parseModule()
        val module = parsed.module
        if (module == null || parsed.diagnostics.isNotEmpty()) {
            return FrontendResult(module, null, emptyList(), parsed.diagnostics, emptyList())
        }

        val lowered = CbpvLowerer().lower(module)
        return FrontendResult(
            module = module,
            computation = lowered.computation,
            lexDiagnostics = emptyList(),
            parseDiagnostics = emptyList(),
            lowerDiagnostics = lowered.diagnostics,
        )
    }
}
