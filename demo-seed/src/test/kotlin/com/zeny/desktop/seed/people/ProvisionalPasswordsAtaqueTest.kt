package com.zeny.desktop.seed.people

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvisionalPasswordsAtaqueTest {
    private val samples = List(3000) { ProvisionalPasswords.next() }

    @Test
    fun noRepetitionAcrossThousands() {
        assertEquals(samples.size, samples.map { it.value }.toSet().size)
    }

    @Test
    fun everySampleHasLengthWithinServerLimitsAndAllFourClasses() {
        samples.map { it.value }.forEach { p ->
            assertEquals(24, p.length)
            assertTrue(p.any { it in 'A'..'Z' } && p.any { it in 'a'..'z' } && p.any { it in '0'..'9' })
            assertTrue(p.any { !it.isLetterOrDigit() })
        }
    }

    @Test
    fun onlyPrintableNonWhitespaceCharactersSafeForJson() {
        val forbidden = "\"\\ "
        samples.map { it.value }.forEach { p ->
            assertTrue(p.all { it.code in 33..126 }, p)
            assertTrue(p.none { it in forbidden }, p)
        }
    }

    @Test
    fun guaranteedClassesAreNotPinnedToFixedPositions() {
        for (pos in 0 until 4) {
            val kinds =
                samples
                    .map { it.value[pos] }
                    .map {
                        when {
                            it.isUpperCase() -> 'U'
                            it.isLowerCase() -> 'L'
                            it.isDigit() -> 'D'
                            else -> 'S'
                        }
                    }.toSet()
            assertEquals(4, kinds.size, "posição $pos sempre da mesma classe")
        }
    }

    @Test
    fun noPositionIsConstantAndCharactersSpreadOverTheAlphabet() {
        for (pos in 0 until 24) {
            assertTrue(samples.map { it.value[pos] }.toSet().size > 10, "posição $pos quase constante")
        }
    }

    @Test
    fun toStringNeverRevealsThePassword() {
        samples.take(50).forEach { assertFalse(it.toString().contains(it.value)) }
    }
}
