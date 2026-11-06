package com.nguevara.obdpulse.vehicle

/**
 * Lector/escritor JSON mínimo (sin dependencias), suficiente para la base de vehículos y los perfiles.
 * Devuelve: Map<String, Any?>, List<Any?>, String, Double, Boolean o null.
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("JSON: texto sobrante en la posición ${p.i}")
        return v
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format(java.util.Locale.US, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun atEnd() = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("JSON: fin inesperado")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("JSON: carácter '$c' en la posición $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("JSON: se esperaba $word en la posición $i")
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDouble()
        }

        private fun str(): String {
            i++ // comilla inicial
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("JSON: texto sin cerrar")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("JSON: escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            skipWs()
            if (s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out += value()
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("JSON: se esperaba , o ] en la posición ${i - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                if (s[i] != '"') throw IllegalArgumentException("JSON: se esperaba una clave en la posición $i")
                val k = str()
                skipWs()
                if (s[i++] != ':') throw IllegalArgumentException("JSON: se esperaba : en la posición ${i - 1}")
                out[k] = value()
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("JSON: se esperaba , o } en la posición ${i - 1}")
                }
            }
        }
    }
}

// Ayudas para leer mapas JSON sin tanto "as?"
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(key: String): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()

internal fun Map<String, Any?>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

internal fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

internal fun Map<String, Any?>.dbl(key: String): Double? = (this[key] as? Number)?.toDouble()

internal fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false

internal fun Map<String, Any?>.strings(key: String): List<String> = list(key).mapNotNull { (it as? String)?.takeIf { s -> s.isNotBlank() } }
