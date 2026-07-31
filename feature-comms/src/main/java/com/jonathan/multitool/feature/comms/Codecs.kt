package com.jonathan.multitool.feature.comms

import kotlin.math.abs
import kotlin.math.roundToInt

enum class Mode(val label: String, val tag: String) {
    MORSE("Morse", "MRS"),
    UART("ASCII", "UART"),
    FAST("Fast", "HUFF")
}

/**
 * Every codec speaks the same physical layer: a list of on/off symbols out, a stream of
 * (level, duration) runs in. Decoding from runs rather than a sampled bit clock means the
 * receiver re-synchronises at every transition and never accumulates phase error.
 */
interface Codec {
    fun encode(text: String): List<Boolean>
    fun reset()
    /** Returns whatever text this run completed — usually empty. */
    fun pushRun(level: Boolean, durationNs: Long, symbolNs: Long): String
    /** Symbols on the wire for this text, for the time estimate in the UI. */
    fun symbolCount(text: String): Int = encode(text).size
}

// ─────────────────────────────────── Morse ───────────────────────────────────

/**
 * Morse stays for human communication: a person can read the flashes, and the decoder
 * *measures* the sender's speed instead of assuming it, so it also works against a human
 * keying a torch by hand or another app at a different setting.
 */
class MorseCodec : Codec {

    private var dotEst = 0.0
    private var dashEst = 0.0
    private val symbol = StringBuilder()

    override fun reset() { dotEst = 0.0; dashEst = 0.0; symbol.setLength(0) }

    override fun encode(text: String): List<Boolean> {
        val out = ArrayList<Boolean>()
        val words = text.trim().uppercase().split(Regex("\\s+"))
        words.forEachIndexed { wi, word ->
            word.forEachIndexed { ci, ch ->
                val code = TABLE[ch] ?: return@forEachIndexed
                code.forEachIndexed { ei, e ->
                    repeat(if (e == '-') 3 else 1) { out.add(true) }
                    if (ei != code.lastIndex) out.add(false)          // 1 unit between elements
                }
                if (ci != word.lastIndex) repeat(3) { out.add(false) } // 3 units between letters
            }
            if (wi != words.lastIndex) repeat(7) { out.add(false) }    // 7 units between words
        }
        return out
    }

    override fun pushRun(level: Boolean, durationNs: Long, symbolNs: Long): String {
        if (dotEst == 0.0) { dotEst = symbolNs.toDouble(); dashEst = 3.0 * symbolNs }
        val d = durationNs.toDouble()
        if (level) {
            val isDot = abs(d - dotEst) <= abs(d - dashEst)
            if (isDot) {
                dotEst = 0.75 * dotEst + 0.25 * d
                if (dashEst < 2.2 * dotEst) dashEst = 3.0 * dotEst      // keep the classes apart
            } else {
                dashEst = 0.75 * dashEst + 0.25 * d
                if (dashEst < 2.2 * dotEst) dotEst = dashEst / 3.0
            }
            symbol.append(if (isDot) '.' else '-')
            if (symbol.length > 8) symbol.setLength(0)                   // nonsense: drop it
            return ""
        }
        // a gap: 1 unit inside a letter, 3 between letters, 7 between words
        return when {
            d < 2.0 * dotEst -> ""
            d < 5.0 * dotEst -> flush()
            else -> flush() + " "
        }
    }

    private fun flush(): String {
        if (symbol.isEmpty()) return ""
        val s = symbol.toString()
        symbol.setLength(0)
        return (REVERSE[s] ?: '·').toString()
    }

    /** Human-readable dots and dashes, for showing what's being sent. */
    fun pattern(text: String): String =
        text.trim().uppercase().map { TABLE[it] ?: "" }.filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        val TABLE: Map<Char, String> = mapOf(
            'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
            'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
            'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
            'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
            'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
            'Z' to "--..",
            '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
            '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
            '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '/' to "-..-.",
            '-' to "-....-", ':' to "---...", '\'' to ".----.", '!' to "-.-.--",
            '=' to "-...-", '+' to ".-.-."
        )
        val REVERSE: Map<String, Char> = TABLE.entries.associate { (k, v) -> v to k }
    }
}

// ──────────────────────────────── ASCII / UART ────────────────────────────────

/**
 * 8-N-1 over light, but **idle dark**: textbook UART idles high, which here would mean the torch
 * burning continuously between characters — battery, LED heat, and a saturated receiver. So idle
 * is off and the start bit is a flash.
 *
 * Both ends are the same app, so the symbol rate is a shared constant and no rate negotiation is
 * needed; the start bit re-aligns phase at every character, and clock drift over a 10-symbol frame
 * is measured in microseconds.
 */
class UartCodec : Codec {

    private var bits = ArrayDeque<Boolean>()
    private var framingErrors = 0
    val errors: Int get() = framingErrors

    override fun reset() { bits.clear(); framingErrors = 0 }

    override fun encode(text: String): List<Boolean> {
        val out = ArrayList<Boolean>()
        for (ch in text) {
            val c = ch.code and 0xFF
            out.add(true)                                   // start
            for (i in 0 until 8) out.add((c shr i) and 1 == 1)  // LSB first
            out.add(false)                                  // stop
            out.add(false)                                  // idle guard
        }
        return out
    }

    override fun pushRun(level: Boolean, durationNs: Long, symbolNs: Long): String {
        repeat(unitsIn(durationNs, symbolNs)) { bits.addLast(level) }
        val sb = StringBuilder()
        while (true) {
            while (bits.isNotEmpty() && !bits.first()) bits.removeFirst()   // skip idle
            if (bits.size < 10) break
            bits.removeFirst()                                              // start bit
            var v = 0
            for (i in 0 until 8) if (bits.removeFirst()) v = v or (1 shl i)
            val stop = bits.removeFirst()
            if (stop) { framingErrors++; continue }                         // stop must be dark
            sb.append(if (v in 32..126 || v == 10) v.toChar() else '·')
        }
        return sb.toString()
    }
}

// ───────────────────────────── Fast (English) ─────────────────────────────

/**
 * Static Huffman over 46 symbols — letters, digits, common punctuation, end-of-message — built
 * from English character frequencies. Averages 4.41 bits per character against 10 symbols for an
 * ASCII UART frame, so roughly 2.25× the throughput for text.
 *
 * The table is shipped in both binaries, which is the whole point: the model costs nothing to
 * transmit because both ends already have it.
 *
 * Bit-stuffing caps runs at four identical symbols. That protects the receiver's adaptive
 * threshold (a long steady run gives it no edges to track while the camera's exposure drifts) and
 * keeps run-length rounding accurate. It also frees `111110` as a start delimiter that stuffed
 * data can never contain.
 */
class FastCodec : Codec {

    private val rx = StringBuilder()
    private var state = 0            // 0 = hunting delimiter, 1 = payload
    private var hunt = 0
    private var run = 0
    private var lastBit = false
    private var code = StringBuilder()
    private var crcBits = 0
    private var crcVal = 0
    private var text = StringBuilder()
    private var status = ""
    val lastStatus: String get() = status

    override fun reset() {
        rx.setLength(0); state = 0; hunt = 0; run = 0
        code.setLength(0); crcBits = 0; crcVal = 0; text.setLength(0); status = ""
    }

    /** Anything outside the table becomes a space; the UI warns when that happens. */
    fun sanitize(s: String): String =
        s.uppercase().map { if (CODES.containsKey(it)) it else if (it.isWhitespace()) ' ' else ' ' }
            .joinToString("").replace(Regex(" +"), " ")

    fun unsupportedCount(s: String): Int =
        s.uppercase().count { !CODES.containsKey(it) && !it.isWhitespace() }

    override fun encode(text: String): List<Boolean> {
        val clean = sanitize(text)
        val out = ArrayList<Boolean>()
        repeat(8) { out.add(true); out.add(false) }             // preamble: 1010…
        listOf(true, true, true, true, true, false).forEach { out.add(it) }   // delimiter 111110

        val stuffed = ArrayList<Boolean>()
        var runLen = 0
        var last = false
        fun put(b: Boolean) {
            if (stuffed.isNotEmpty() && b == last) runLen++ else { runLen = 1; last = b }
            stuffed.add(b)
            if (runLen == 4) { stuffed.add(!b); last = !b; runLen = 1 }
        }
        for (ch in clean) CODES[ch]?.forEach { put(it == '1') }
        CODES[EOM]?.forEach { put(it == '1') }             // end of message
        val crc = crc8(clean)
        for (i in 7 downTo 0) put((crc shr i) and 1 == 1)
        out.addAll(stuffed)
        return out
    }

    override fun pushRun(level: Boolean, durationNs: Long, symbolNs: Long): String {
        var out = ""
        repeat(unitsIn(durationNs, symbolNs)) { out += pushBit(level) }
        return out
    }

    private fun pushBit(b: Boolean): String {
        if (state == 0) {
            // hunt for 111110
            hunt = if (b) minOf(hunt + 1, 5) else if (hunt >= 5) { state = 1; resetPayload(); 0 } else 0
            return ""
        }
        // un-stuff: after four identical bits the next one is filler
        if (run >= 4) { run = 0; lastBit = b; return "" }
        if (b == lastBit) run++ else { run = 1; lastBit = b }

        if (crcBits > 0) {
            crcVal = (crcVal shl 1) or (if (b) 1 else 0)
            crcBits--
            if (crcBits == 0) {
                val body = text.toString()
                val ok = crc8(body) == (crcVal and 0xFF)
                status = if (ok) "CRC ok" else "CRC FAIL"
                state = 0; hunt = 0
                return body + if (ok) "" else " ⚠"
            }
            return ""
        }

        code.append(if (b) '1' else '0')
        val ch = DECODE[code.toString()]
        if (ch != null) {
            code.setLength(0)
            if (ch == EOM) { crcBits = 8; crcVal = 0; return "" }
            text.append(ch)
        } else if (code.length > 12) {
            code.setLength(0)                                    // lost the plane; re-hunt
            state = 0; hunt = 0; status = "sync lost"
        }
        return ""
    }

    private fun resetPayload() {
        run = 0; lastBit = false; code.setLength(0); crcBits = 0; crcVal = 0; text.setLength(0)
    }

    companion object {
        /** End-of-message marker; never appears in user text. */
        const val EOM = '\u0000'

        fun crc8(s: String): Int {
            var crc = 0
            for (ch in s) {
                crc = crc xor (ch.code and 0xFF)
                repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
            }
            return crc and 0xFF
        }

        /** 46 symbols, 4.405 bits/char average — built from English character frequencies. */
        val CODES: Map<Char, String> = mapOf(
            'E' to "001", ' ' to "111",
            'R' to "0001", 'S' to "0100", 'N' to "0101", 'I' to "0110", 'O' to "0111",
            'A' to "1001", 'T' to "1100",
            'U' to "00000", 'L' to "10100", 'D' to "10101", 'H' to "11011",
            'P' to "100000", 'G' to "100010", 'Y' to "100011", 'W' to "101100",
            'F' to "101110", 'M' to "101111", 'C' to "110100",
            ',' to "0000110", 'V' to "1000011", '.' to "1011011", 'B' to "1101011",
            '\'' to "00001000", '2' to "00001001", '0' to "00001110", '1' to "10000101",
            'K' to "11010101",
            'X' to "000010100", 'J' to "000010101", ':' to "000010110", '6' to "000010111",
            '7' to "000011110", '8' to "000011111", '9' to "100001000", '?' to "100001001",
            '-' to "101101000", '4' to "101101001", '5' to "101101011", '3' to "110101001",
            'Q' to "1011010100", '!' to "1011010101", '/' to "1101010000",
            EOM to "11010100010", 'Z' to "11010100011"
        )
        val DECODE: Map<String, Char> = CODES.entries.associate { (k, v) -> v to k }
    }
}

/** Runs become symbols by rounding — safe while jitter stays well under half a symbol. */
internal fun unitsIn(durationNs: Long, symbolNs: Long): Int =
    (durationNs.toDouble() / symbolNs).roundToInt().coerceIn(1, 40)

fun codecFor(mode: Mode): Codec = when (mode) {
    Mode.MORSE -> MorseCodec()
    Mode.UART -> UartCodec()
    Mode.FAST -> FastCodec()
}
