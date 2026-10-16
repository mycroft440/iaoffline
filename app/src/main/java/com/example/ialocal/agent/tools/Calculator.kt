package com.example.ialocal.agent.tools

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.floor

/**
 * Exact arithmetic for the agents: small models get multi-digit math wrong, a parser does not.
 * Supports + - * / % ^, parentheses, scientific notation, the constants pi and e and common
 * functions (sqrt, cbrt, abs, ln, log, log2, exp, sin, cos, tan, asin, acos, atan, floor, ceil,
 * round, min, max, pow, fact). Angles are in radians.
 */
object Calculator {
    fun evaluate(expression: String): Double {
        val normalized = expression
            .replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-')
            .replace("**", "^")
        require(normalized.length <= MAX_EXPRESSION_CHARS) { "Expressão longa demais." }
        val parser = Parser(normalized)
        val value = parser.parseExpression()
        parser.expectEnd()
        if (value.isNaN()) throw ArithmeticException("Resultado indefinido.")
        if (value.isInfinite()) throw ArithmeticException("Resultado infinito (divisão por zero ou número grande demais).")
        return value
    }

    /** Integers without decimals, everything else with up to 12 significant digits. */
    fun format(value: Double): String {
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return BigDecimal(value).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private class Parser(private val text: String) {
        private var position = 0

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                value = when {
                    eat('+') -> value + parseTerm()
                    eat('-') -> value - parseTerm()
                    else -> return value
                }
            }
        }

        fun expectEnd() {
            skipSpaces()
            if (position < text.length) throw IllegalArgumentException("Símbolo inesperado: '${text[position]}'.")
        }

        private fun parseTerm(): Double {
            var value = parseUnary()
            while (true) {
                value = when {
                    eat('*') -> value * parseUnary()
                    eat('/') -> {
                        val divisor = parseUnary()
                        if (divisor == 0.0) throw ArithmeticException("Divisão por zero.")
                        value / divisor
                    }
                    eat('%') -> {
                        val divisor = parseUnary()
                        if (divisor == 0.0) throw ArithmeticException("Divisão por zero.")
                        value % divisor
                    }
                    else -> return value
                }
            }
        }

        // Unary minus binds looser than ^, so -2^2 = -4.
        private fun parseUnary(): Double = when {
            eat('-') -> -parseUnary()
            eat('+') -> parseUnary()
            else -> parsePower()
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            return if (eat('^')) Math.pow(base, parseUnary()) else base
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            if (eat('(')) {
                val value = parseExpression()
                expect(')')
                return value
            }
            val start = position
            if (position < text.length && (text[position].isDigit() || text[position] == '.')) {
                while (position < text.length && (text[position].isDigit() || text[position] == '.')) position++
                if (position < text.length && (text[position] == 'e' || text[position] == 'E') &&
                    position + 1 < text.length && (text[position + 1].isDigit() || text[position + 1] in "+-")
                ) {
                    position += 2
                    while (position < text.length && text[position].isDigit()) position++
                }
                return text.substring(start, position).toDoubleOrNull()
                    ?: throw IllegalArgumentException("Número inválido: ${text.substring(start, position)}")
            }
            if (position < text.length && text[position].isLetter()) {
                while (position < text.length && (text[position].isLetterOrDigit() || text[position] == '_')) position++
                val name = text.substring(start, position).lowercase()
                if (eat('(')) {
                    val arguments = mutableListOf(parseExpression())
                    while (eat(',') || eat(';')) arguments += parseExpression()
                    expect(')')
                    return function(name, arguments)
                }
                return when (name) {
                    "pi", "π" -> Math.PI
                    "e" -> Math.E
                    else -> throw IllegalArgumentException("Nome desconhecido: $name")
                }
            }
            throw IllegalArgumentException(
                if (position < text.length) "Símbolo inesperado: '${text[position]}'." else "Expressão incompleta.",
            )
        }

        private fun function(name: String, args: List<Double>): Double {
            fun one(): Double {
                if (args.size != 1) throw IllegalArgumentException("$name espera 1 argumento.")
                return args[0]
            }
            fun two(): Pair<Double, Double> {
                if (args.size != 2) throw IllegalArgumentException("$name espera 2 argumentos.")
                return args[0] to args[1]
            }
            return when (name) {
                "sqrt", "raiz" -> one().also { if (it < 0) throw ArithmeticException("Raiz de número negativo.") }.let(Math::sqrt)
                "cbrt" -> Math.cbrt(one())
                "abs" -> abs(one())
                "ln" -> Math.log(one())
                "log", "log10" -> if (args.size == 2) Math.log(args[0]) / Math.log(args[1]) else Math.log10(one())
                "log2" -> Math.log(one()) / Math.log(2.0)
                "exp" -> Math.exp(one())
                "sin", "sen" -> Math.sin(one())
                "cos" -> Math.cos(one())
                "tan", "tg" -> Math.tan(one())
                "asin" -> Math.asin(one())
                "acos" -> Math.acos(one())
                "atan" -> Math.atan(one())
                "floor" -> floor(one())
                "ceil" -> Math.ceil(one())
                "round" -> if (args.size == 2) {
                    val (value, digits) = two()
                    BigDecimal(value).setScale(digits.toInt(), java.math.RoundingMode.HALF_UP).toDouble()
                } else {
                    Math.round(one()).toDouble()
                }
                "min" -> args.minOrNull() ?: throw IllegalArgumentException("min espera argumentos.")
                "max" -> args.maxOrNull() ?: throw IllegalArgumentException("max espera argumentos.")
                "pow" -> two().let { (base, exponent) -> Math.pow(base, exponent) }
                "fact", "factorial" -> factorial(one())
                else -> throw IllegalArgumentException("Função desconhecida: $name")
            }
        }

        private fun factorial(value: Double): Double {
            if (value < 0 || value != floor(value) || value > 170) {
                throw ArithmeticException("Fatorial definido só para inteiros de 0 a 170.")
            }
            var result = 1.0
            for (i in 2..value.toInt()) result *= i
            return result
        }

        private fun eat(char: Char): Boolean {
            skipSpaces()
            if (position < text.length && text[position] == char) {
                position++
                return true
            }
            return false
        }

        private fun expect(char: Char) {
            if (!eat(char)) throw IllegalArgumentException("Esperava '$char'.")
        }

        private fun skipSpaces() {
            while (position < text.length && text[position].isWhitespace()) position++
        }
    }

    private const val MAX_EXPRESSION_CHARS = 500
}
