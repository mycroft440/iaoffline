package com.example.ialocal.agent

import com.example.ialocal.agent.tools.Calculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalculatorTest {
    private fun calc(expression: String) = Calculator.format(Calculator.evaluate(expression))

    @Test
    fun followsOperatorPrecedence() {
        assertEquals("14", calc("2 + 3 * 4"))
        assertEquals("20", calc("(2 + 3) * 4"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("512", calc("2^3^2"))
        assertEquals("1", calc("10 % 3"))
        assertEquals("2.5", calc("5 / 2"))
        assertEquals("6", calc("2 × 3"))
        assertEquals("3", calc("9 ÷ 3"))
    }

    @Test
    fun formatsResultsWithoutFloatingPointNoise() {
        assertEquals("1111111101", calc("123456789 * 9"))
        assertEquals("0.1", calc("0.3 - 0.2"))
        assertEquals("1500000", calc("1.5e6"))
        assertEquals("0.333333333333", calc("1/3"))
    }

    @Test
    fun supportsFunctionsAndConstants() {
        assertEquals("12", calc("sqrt(144)"))
        assertEquals("3.14159265359", calc("pi"))
        assertEquals("120", calc("fact(5)"))
        assertEquals("3", calc("log(1000)"))
        assertEquals("7", calc("max(2, 7, 3)"))
        assertEquals("3.14", calc("round(3.14159, 2)"))
        assertEquals("1", calc("cos(0)"))
    }

    @Test
    fun rejectsInvalidInput() {
        assertThrows(ArithmeticException::class.java) { Calculator.evaluate("1 / 0") }
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("2 +") }
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("foo(2)") }
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("2 3") }
    }
}
