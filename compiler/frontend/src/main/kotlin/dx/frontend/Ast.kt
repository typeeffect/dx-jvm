package dx.frontend

sealed interface DxExpr {
    val span: SourceSpan

    data class UnitLiteral(override val span: SourceSpan) : DxExpr
    data class BoolLiteral(val value: Boolean, override val span: SourceSpan) : DxExpr
    data class IntLiteral(val value: Long, override val span: SourceSpan) : DxExpr
    data class StringLiteral(val value: String, override val span: SourceSpan) : DxExpr
    data class Variable(val name: String, override val span: SourceSpan) : DxExpr
    data class Val(
        val name: String,
        val value: DxExpr,
        val body: DxExpr,
        override val span: SourceSpan,
    ) : DxExpr

    data class Lambda(
        val parameter: String,
        val body: DxExpr,
        override val span: SourceSpan,
    ) : DxExpr

    data class Apply(
        val function: DxExpr,
        val argument: DxExpr,
        override val span: SourceSpan,
    ) : DxExpr

    data class Thunk(val body: DxExpr, override val span: SourceSpan) : DxExpr
    data class Force(val value: DxExpr, override val span: SourceSpan) : DxExpr
    data class PairExpr(val first: DxExpr, val second: DxExpr, override val span: SourceSpan) : DxExpr
}

data class DxModule(
    val expression: DxExpr,
)
