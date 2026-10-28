package com.odomap.core.region

// Small pull parser over UTF-8 bytes for the bundled region asset (core has no JSON dependency). Trusted input: malformed JSON throws.

internal class JsonReader(private val b: ByteArray) {
    private var i = 0

    /** next structural character (whitespace skipped), '\u0000' at the end */
    fun peek(): Char {
        skipWs()
        return if (i < b.size) (b[i].toInt() and 0xff).toChar() else '\u0000'
    }

    fun beginObject() = expect('{')

    fun endObject() = expect('}')

    fun beginArray() = expect('[')

    fun endArray() = expect(']')

    /** another element follows in the current array/object (the separating comma is consumed) */
    fun hasNext(): Boolean {
        val c = peek()
        if (c == ',') {
            i += 1
            return true
        }
        return c != ']' && c != '}'
    }

    fun nextName(): String {
        val name = nextString()
        expect(':')
        return name
    }

    fun nextString(): String {
        expect('"')
        val start = i
        var plain = true
        while (true) {
            if (i >= b.size) fail("unterminated string")
            val c = b[i].toInt()
            if (c == '"'.code) break
            if (c == '\\'.code) {
                plain = false
                i += 2
            } else {
                i += 1
            }
        }
        val end = i
        i += 1
        return if (plain) String(b, start, end - start, Charsets.UTF_8) else unescape(String(b, start, end - start, Charsets.UTF_8))
    }

    fun nextLong(): Long {
        skipWs()
        val start = i
        var neg = false
        if (i < b.size && b[i].toInt() == '-'.code) {
            neg = true
            i += 1
        }
        var value = 0L
        var digits = 0
        while (i < b.size && b[i] >= '0'.code.toByte() && b[i] <= '9'.code.toByte()) {
            value = value * 10 + (b[i] - '0'.code.toByte())
            i += 1
            digits += 1
        }
        if (i < b.size && (b[i].toInt() == '.'.code || b[i].toInt() == 'e'.code || b[i].toInt() == 'E'.code)) {
            i = start
            return nextDouble().toLong()
        }
        if (digits == 0) fail("number expected")
        return if (neg) -value else value
    }

    fun nextInt(): Int {
        val v = nextLong()
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) fail("int out of range")
        return v.toInt()
    }

    fun nextDouble(): Double {
        skipWs()
        val start = i
        while (i < b.size && isNumberChar(b[i].toInt())) i += 1
        if (i == start) fail("number expected")
        return String(b, start, i - start, Charsets.US_ASCII).toDouble()
    }

    fun nextIntArray(): IntArray {
        beginArray()
        var out = IntArray(16)
        var n = 0
        while (hasNext()) {
            if (n == out.size) out = out.copyOf(n * 2)
            out[n++] = nextInt()
        }
        endArray()
        return out.copyOf(n)
    }

    fun nextDoubleArray(): DoubleArray {
        beginArray()
        val out = ArrayList<Double>()
        while (hasNext()) out.add(nextDouble())
        endArray()
        return out.toDoubleArray()
    }

    fun nextStringList(): List<String> {
        beginArray()
        val out = ArrayList<String>()
        while (hasNext()) out.add(nextString())
        endArray()
        return out
    }

    fun skipValue() {
        when (peek()) {
            '{' -> {
                beginObject()
                while (hasNext()) {
                    nextName()
                    skipValue()
                }
                endObject()
            }
            '[' -> {
                beginArray()
                while (hasNext()) skipValue()
                endArray()
            }
            '"' -> nextString()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> nextDouble()
        }
    }

    private fun literal(word: String) {
        skipWs()
        for (ch in word) {
            if (i >= b.size || b[i].toInt() != ch.code) fail("$word expected")
            i += 1
        }
    }

    private fun expect(ch: Char) {
        skipWs()
        if (i >= b.size || b[i].toInt() != ch.code) fail("'$ch' expected")
        i += 1
    }

    private fun skipWs() {
        while (i < b.size) {
            val c = b[i].toInt()
            if (c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) return
            i += 1
        }
    }

    private fun isNumberChar(c: Int): Boolean =
        (c >= '0'.code && c <= '9'.code) || c == '-'.code || c == '+'.code || c == '.'.code || c == 'e'.code || c == 'E'.code

    private fun unescape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var k = 0
        while (k < raw.length) {
            val c = raw[k++]
            if (c != '\\') {
                sb.append(c)
                continue
            }
            when (val e = raw[k++]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    sb.append(raw.substring(k, k + 4).toInt(16).toChar())
                    k += 4
                }
                else -> sb.append(e)
            }
        }
        return sb.toString()
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException("regions json: $message at byte $i")
}
