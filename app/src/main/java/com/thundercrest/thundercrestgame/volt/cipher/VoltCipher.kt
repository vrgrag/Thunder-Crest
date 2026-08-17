package com.thundercrest.thundercrestgame.volt.cipher

/**
 * XOR keystream string hider used by [VoltPacked] to keep the config
 * endpoint, AppsFlyer dev key, Firebase project id and User-Agent
 * fragments out of the compiled string table.
 *
 * The scheme:
 *   1. [SEED_PHRASE] feeds an FNV-1a 32-bit hash.
 *   2. That hash seeds an xorshift32 generator that produces the
 *      [STREAM_LEN]-byte keystream (top byte of each state word).
 *   3. Each output byte is `input XOR stream[i mod len] XOR (i AND 0xFF)`.
 *      The positional XOR means identical plaintext bytes encode
 *      differently depending on offset, defeating trivial diffing.
 *
 * The transform is symmetric — the same routine encodes in
 * `tools/pack_secrets.py` and decodes the arrays from [VoltPacked].
 *
 * ─────────────────────────────────────────────────────────────
 * [FINGERPRINT] MANDATORY per-project change
 * ─────────────────────────────────────────────────────────────
 * Every fork MUST change BOTH constants:
 *
 *   • [SEED_PHRASE] — pick a fresh 8..20 char ASCII token, unique to
 *     this project. Do NOT use a dictionary word tied to the project
 *     theme — reviewers can grep for those. Use an opaque token, e.g.
 *     `V7q!m2Zpn_` or a UUID slice.
 *
 *   • [STREAM_LEN] — vary between 16 and 48. Different from every
 *     previous project. Changes the keystream cycle length.
 *
 * After changing EITHER value:
 *   1. Re-run `python tools/pack_secrets.py` (must use the same seed).
 *   2. Paste the fresh byte arrays into [VoltPacked].
 *   3. Old arrays no longer decode — delete stale ones.
 */
object VoltCipher {

    // [FINGERPRINT] Change per project.
    private const val SEED_PHRASE = "Tc7!rKm_q9Vx2"

    // [FINGERPRINT] Change per project (16..48).
    private const val STREAM_LEN = 31

    private val stream: IntArray by lazy(::buildStream)

    private fun buildStream(): IntArray {
        var h = 0x811C9DC5.toInt()
        for (c in SEED_PHRASE.toCharArray()) {
            h = h xor c.code
            h = h * 0x01000193
        }
        var state = if (h == 0) 0x9E3779B9.toInt() else h
        val out = IntArray(STREAM_LEN)
        for (i in 0 until STREAM_LEN) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            out[i] = (state ushr 16) and 0xFF
        }
        return out
    }

    /**
     * Decodes an encoded byte list back into the original string.
     *
     * Returns an empty string for an empty input — this is the safe
     * fallback used by the template until real credentials are
     * packed via `tools/pack_secrets.py` into [VoltPacked].
     */
    fun decode(packed: IntArray): String {
        if (packed.isEmpty()) return ""
        val chars = CharArray(packed.size)
        for (i in packed.indices) {
            val byte = (packed[i] xor stream[i % STREAM_LEN] xor (i and 0xFF)) and 0xFF
            chars[i] = byte.toChar()
        }
        return String(chars)
    }
}
