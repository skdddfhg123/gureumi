package com.example.pleosapp.ui.share

/**
 * QR 코드를 만듭니다. 차 앱은 통신하지 않고 그림만 그리므로 외부 라이브러리 없이 직접 씁니다.
 * 바이트 모드, 오류 복원 M, 버전 1~10만 다룹니다. 엽서 주소는 이 안에 충분히 들어갑니다.
 */
object QrEncoder {

    /** 칸의 어둡고 밝음. 첫 줄 왼쪽부터 size × size 칸입니다. */
    class Matrix(val size: Int, private val cells: BooleanArray) {
        fun dark(x: Int, y: Int): Boolean = cells[y * size + x]
    }

    /** 버전별 전체 코드워드, 블록당 오류 코드워드, 블록 수. 버전 1이 첫 칸입니다. */
    private val TOTAL_CODEWORDS = intArrayOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
    private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    private val ALIGNMENT = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50),
    )

    private const val MAX_VERSION = 10
    private const val FORMAT_MASK = 0x5412
    private const val FORMAT_GENERATOR = 0x537
    private const val VERSION_GENERATOR = 0x1F25

    /** M의 표시값은 0입니다. */
    private const val LEVEL_M = 0

    fun encode(text: String): Matrix {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val version = (1..MAX_VERSION).firstOrNull { bytes.size <= capacity(it) }
            ?: throw IllegalArgumentException("QR에 담기에 긴 내용입니다: ${bytes.size}바이트")
        val data = dataCodewords(bytes, version)
        val codewords = interleave(data, version)
        val grid = Grid(version)
        grid.placeFunctions()
        grid.placeData(codewords)
        return grid.finish()
    }

    /** 바이트 모드로 담을 수 있는 최대 길이. */
    private fun capacity(version: Int): Int {
        val index = version - 1
        val dataWords = TOTAL_CODEWORDS[index] - ECC_PER_BLOCK[index] * BLOCKS[index]
        return (dataWords * 8 - 4 - countBits(version)) / 8
    }

    private fun countBits(version: Int): Int = if (version < 10) 8 else 16

    private fun dataCodewords(bytes: ByteArray, version: Int): IntArray {
        val index = version - 1
        val size = TOTAL_CODEWORDS[index] - ECC_PER_BLOCK[index] * BLOCKS[index]
        val bits = BitWriter(size)
        bits.put(0b0100, 4)
        bits.put(bytes.size, countBits(version))
        bytes.forEach { bits.put(it.toInt() and 0xFF, 8) }
        bits.put(0, minOf(4, size * 8 - bits.length))
        bits.put(0, (8 - bits.length % 8) % 8)
        var pad = 0xEC
        while (bits.length < size * 8) {
            bits.put(pad, 8)
            pad = pad xor (0xEC xor 0x11)
        }
        return bits.words
    }

    private class BitWriter(size: Int) {
        val words = IntArray(size)
        var length = 0
            private set

        fun put(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                val bit = (value ushr i) and 1
                words[length / 8] = words[length / 8] or (bit shl (7 - length % 8))
                length++
            }
        }
    }

    /** 블록으로 나눠 오류 코드워드를 붙이고, 한 칸씩 번갈아 엮습니다. */
    private fun interleave(data: IntArray, version: Int): IntArray {
        val index = version - 1
        val total = TOTAL_CODEWORDS[index]
        val ecc = ECC_PER_BLOCK[index]
        val blocks = BLOCKS[index]
        val shortBlocks = blocks - total % blocks
        val shortLength = total / blocks
        val divisor = reedSolomonDivisor(ecc)
        val dataBlocks = ArrayList<IntArray>()
        val eccBlocks = ArrayList<IntArray>()
        var offset = 0
        for (i in 0 until blocks) {
            val length = shortLength - ecc + if (i < shortBlocks) 0 else 1
            val block = data.copyOfRange(offset, offset + length)
            offset += length
            dataBlocks += block
            eccBlocks += remainder(block, divisor)
        }
        val out = IntArray(total)
        var cursor = 0
        for (i in 0 until shortLength - ecc + 1) {
            for (block in dataBlocks) {
                if (i < block.size) out[cursor++] = block[i]
            }
        }
        for (i in 0 until ecc) {
            for (block in eccBlocks) out[cursor++] = block[i]
        }
        return out
    }

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = multiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = multiply(root, 2)
        }
        return result
    }

    private fun remainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (value in data) {
            val factor = value xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
        }
        return result
    }

    /** GF(2^8)의 곱셈. 줄임 다항식은 0x11D입니다. */
    private fun multiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private class Grid(private val version: Int) {
        val size = version * 4 + 17
        private val modules = BooleanArray(size * size)
        private val reserved = BooleanArray(size * size)

        private fun at(x: Int, y: Int) = y * size + x

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[at(x, y)] = dark
            reserved[at(x, y)] = true
        }

        fun placeFunctions() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            placeFinder(3, 3)
            placeFinder(size - 4, 3)
            placeFinder(3, size - 4)
            val centers = ALIGNMENT[version - 1]
            for (i in centers.indices) {
                for (j in centers.indices) {
                    val corner = (i == 0 && j == 0) || (i == 0 && j == centers.lastIndex) || (i == centers.lastIndex && j == 0)
                    if (!corner) placeAlignment(centers[i], centers[j])
                }
            }
            placeFormat(0)
            placeVersion()
        }

        private fun placeFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x !in 0 until size || y !in 0 until size) continue
                    val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    set(x, y, distance != 2 && distance != 4)
                }
            }
        }

        private fun placeAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) {
                    set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                }
            }
        }

        private fun placeFormat(mask: Int) {
            val data = (LEVEL_M shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * FORMAT_GENERATOR) }
            val bits = ((data shl 10) or rem) xor FORMAT_MASK
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun placeVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * VERSION_GENERATOR) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark)
                set(b, a, dark)
            }
        }

        /** 오른쪽 아래에서 시작해 두 칸 폭으로 지그재그로 올라갔다 내려옵니다. */
        fun placeData(codewords: IntArray) {
            var bit = 0
            val total = codewords.size * 8
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (step in 0 until size) {
                    for (column in 0..1) {
                        val x = right - column
                        val upward = (right + 1) and 2 == 0
                        val y = if (upward) size - 1 - step else step
                        if (!reserved[at(x, y)] && bit < total) {
                            modules[at(x, y)] = (codewords[bit ushr 3] ushr (7 - (bit and 7))) and 1 != 0
                            bit++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val flip = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (flip && !reserved[at(x, y)]) modules[at(x, y)] = !modules[at(x, y)]
                }
            }
        }

        /** 여덟 가지 무늬 중 벌점이 가장 낮은 것을 씁니다. */
        fun finish(): Matrix {
            var best = 0
            var lowest = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                placeFormat(mask)
                val score = penalty()
                if (score < lowest) {
                    lowest = score
                    best = mask
                }
                applyMask(mask)
            }
            applyMask(best)
            placeFormat(best)
            return Matrix(size, modules.copyOf())
        }

        private fun penalty(): Int {
            var score = 0
            for (y in 0 until size) score += lineScore { modules[at(it, y)] }
            for (x in 0 until size) score += lineScore { modules[at(x, it)] }
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val c = modules[at(x, y)]
                    if (c == modules[at(x + 1, y)] && c == modules[at(x, y + 1)] && c == modules[at(x + 1, y + 1)]) score += 3
                }
            }
            val dark = modules.count { it }
            val total = size * size
            val deviation = kotlin.math.abs(dark * 20 - total * 10) / total
            return score + deviation * 10
        }

        /** 같은 색이 길게 이어지거나 찾기 무늬를 닮은 줄에 벌점을 줍니다. */
        private fun lineScore(cell: (Int) -> Boolean): Int {
            var score = 0
            var run = 0
            var color = false
            var history = 0
            for (i in 0 until size) {
                val value = cell(i)
                if (i > 0 && value == color) {
                    run++
                    if (run == 5) score += 3 else if (run > 5) score++
                } else {
                    color = value
                    run = 1
                }
                history = ((history shl 1) and 0x7FF) or (if (value) 1 else 0)
                if (i >= 10 && (history == 0b10111010000 || history == 0b00001011101)) score += 40
            }
            return score
        }
    }
}
