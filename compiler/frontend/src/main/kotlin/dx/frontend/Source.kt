package dx.frontend

data class SourceId(val fileName: String)

data class SourceSpan(
    val source: SourceId,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val column: Int,
) {
    init {
        require(startOffset <= endOffset) { "source span start must not exceed end" }
        require(line > 0) { "line numbers are 1-based" }
        require(column > 0) { "column numbers are 1-based" }
    }
}

data class Spanned<out T>(
    val value: T,
    val span: SourceSpan,
)
