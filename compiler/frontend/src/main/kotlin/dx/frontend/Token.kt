package dx.frontend

enum class TokenKind {
    Identifier,
    Integer,
    String,
    True,
    False,
    Unit,
    Val,
    Fun,
    Thunk,
    Force,
    Pair,
    LParen,
    RParen,
    LBrace,
    RBrace,
    Semicolon,
    Comma,
    Equal,
    Arrow,
    Eof,
}

data class Token(
    val kind: TokenKind,
    val lexeme: String,
    val span: SourceSpan,
)

sealed interface LexDiagnostic {
    data class UnexpectedCharacter(val character: Char, val span: SourceSpan) : LexDiagnostic
    data class UnterminatedString(val span: SourceSpan) : LexDiagnostic
}

data class LexResult(
    val tokens: List<Token>,
    val diagnostics: List<LexDiagnostic>,
)
