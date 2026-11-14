package com.example.pleosapp.share

import com.example.pleosapp.ui.share.QrEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QrEncoderTest {

    private fun finderAt(matrix: QrEncoder.Matrix, left: Int, top: Int) {
        for (y in 0 until 7) {
            for (x in 0 until 7) {
                val ring = x == 0 || x == 6 || y == 0 || y == 6
                val core = x in 2..4 && y in 2..4
                assertEquals("finder ($x,$y)", ring || core, matrix.dark(left + x, top + y))
            }
        }
    }

    @Test
    fun `짧은 주소는 작은 버전에 들어간다`() {
        val matrix = QrEncoder.encode("https://gureumi.pages.dev/#AQFnBwIAAAABAAMABQ")
        assertEquals(33, matrix.size)
    }

    @Test
    fun `찾기 무늬 세 개가 제자리에 있다`() {
        val matrix = QrEncoder.encode("https://example.test/c#abc")
        finderAt(matrix, 0, 0)
        finderAt(matrix, matrix.size - 7, 0)
        finderAt(matrix, 0, matrix.size - 7)
    }

    @Test
    fun `타이밍 줄은 번갈아 나온다`() {
        val matrix = QrEncoder.encode("https://example.test/c#abc")
        for (i in 8 until matrix.size - 8) {
            assertEquals(i % 2 == 0, matrix.dark(i, 6))
            assertEquals(i % 2 == 0, matrix.dark(6, i))
        }
    }

    @Test
    fun `어두운 고정 칸이 있다`() {
        val matrix = QrEncoder.encode("hello")
        assertTrue(matrix.dark(8, matrix.size - 8))
    }

    @Test
    fun `같은 글자는 같은 그림이다`() {
        val a = QrEncoder.encode("같은 글자")
        val b = QrEncoder.encode("같은 글자")
        for (y in 0 until a.size) for (x in 0 until a.size) assertEquals(a.dark(x, y), b.dark(x, y))
    }

    @Test
    fun `버전 7 이상도 만든다`() {
        val matrix = QrEncoder.encode("a".repeat(120))
        assertTrue(matrix.size >= 45)
        assertFalse(matrix.dark(7, 7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `너무 길면 거절한다`() {
        QrEncoder.encode("a".repeat(400))
    }

    /** scripts/check_qr.py가 이 그림을 실제 QR 판독기로 읽어 봅니다. */
    @Test
    fun `판독 시험용 그림을 남긴다`() {
        val folder = File("build/qr-check").apply { mkdirs() }
        SAMPLES.forEachIndexed { index, text ->
            val matrix = QrEncoder.encode(text)
            File(folder, "$index.txt").writeText(text)
            File(folder, "$index.pbm").writeText(buildString {
                append("P1\n${matrix.size} ${matrix.size}\n")
                for (y in 0 until matrix.size) {
                    for (x in 0 until matrix.size) append(if (matrix.dark(x, y)) "1 " else "0 ")
                    append('\n')
                }
            })
        }
    }

    private companion object {
        val SAMPLES = listOf(
            "hello",
            "https://gureumi.pages.dev/#AQFnBwIAAAABAAMABQ",
            "https://example.test/card#" + "x".repeat(60),
            "https://example.test/card#" + "y".repeat(120),
            "https://example.test/card#" + "z".repeat(170),
            "구름이가 보낸 엽서",
        )
    }
}
