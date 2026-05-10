package dx.cli

object DxValuePrinter {
    fun render(value: Any?): String =
        when (value) {
            null -> "unit"
            is String -> value
            is Boolean -> value.toString()
            is Long -> value.toString()
            is Int -> value.toString()
            is Pair<*, *> -> "pair(${render(value.first)}, ${render(value.second)})"
            else -> value.toString()
        }
}
