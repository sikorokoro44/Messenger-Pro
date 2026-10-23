package com.sikorokoro44.messengerpro.remote

object Json {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWhitespace()
        require(parser.finished()) { "Trailing JSON content" }
        return value
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    fun arr(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

    fun str(value: Any?): String? = value as? String

    fun num(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    fun bool(value: Any?): Boolean? = value as? Boolean

    private fun write(builder: StringBuilder, value: Any?) {
        when (value) {
            null -> builder.append("null")
            is String -> quote(builder, value)
            is Boolean -> builder.append(value)
            is Int, is Long, is Short, is Byte -> builder.append(value)
            is Float -> builder.append(if (value.isFinite()) value.toString() else "null")
            is Double -> builder.append(if (value.isFinite()) value.toString() else "null")
            is Map<*, *> -> {
                builder.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) builder.append(',')
                    first = false
                    quote(builder, key.toString())
                    builder.append(':')
                    write(builder, item)
                }
                builder.append('}')
            }
            is Iterable<*> -> {
                builder.append('[')
                var first = true
                for (item in value) {
                    if (!first) builder.append(',')
                    first = false
                    write(builder, item)
                }
                builder.append(']')
            }
            is Array<*> -> write(builder, value.toList())
            else -> quote(builder, value.toString())
        }
    }

    private fun quote(builder: StringBuilder, text: String) {
        builder.append('"')
        for (character in text) {
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> if (character < ' ') {
                    builder.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(character)
                }
            }
        }
        builder.append('"')
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun finished(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun value(): Any? {
            skipWhitespace()
            require(index < text.length) { "Unexpected end of JSON" }
            return when (val character = text[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (character == '-' || character.isDigit()) numberValue() else error("Unexpected character '$character'")
            }
        }

        private fun objectValue(): Map<String, Any?> {
            index++
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek('}')) {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                require(peek('"')) { "Expected object key" }
                val key = stringValue()
                skipWhitespace()
                require(peek(':')) { "Expected ':'" }
                index++
                result[key] = value()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return result
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun arrayValue(): List<Any?> {
            index++
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek(']')) {
                index++
                return result
            }
            while (true) {
                result.add(value())
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return result
                    }
                    else -> error("Expected ',' or ']'")
                }
            }
        }

        private fun stringValue(): String {
            index++
            val builder = StringBuilder()
            while (index < text.length) {
                val character = text[index++]
                when (character) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        require(index < text.length) { "Unterminated escape" }
                        when (val escape = text[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "Invalid unicode escape" }
                                builder.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("Invalid escape '\\$escape'")
                        }
                    }
                    else -> builder.append(character)
                }
            }
            error("Unterminated string")
        }

        private fun numberValue(): Any {
            val start = index
            if (peek('-')) index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            val token = text.substring(start, index)
            return if (token.contains('.') || token.contains('e') || token.contains('E')) {
                token.toDoubleOrNull() ?: error("Invalid number '$token'")
            } else {
                token.toLongOrNull() ?: error("Invalid number '$token'")
            }
        }

        private fun literal(token: String, result: Any?): Any? {
            require(text.startsWith(token, index)) { "Expected '$token'" }
            index += token.length
            return result
        }

        private fun peek(character: Char): Boolean = index < text.length && text[index] == character

        private fun error(message: String): Nothing = throw IllegalArgumentException("$message at index $index")
    }
}
