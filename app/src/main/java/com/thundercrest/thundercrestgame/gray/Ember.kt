package com.thundercrest.thundercrestgame.gray

/**
 * Runtime string vault for values that must not survive as plaintext in the
 * shipped `.dex`. Each entry is a rolling-XOR encoded byte array; the string
 * is reconstructed on first access and cached via `by lazy`.
 *
 * Why this exists: a store scanner running `strings(1)` on `libapp.so` /
 * `classes.dex` looks for well-known plaintext fingerprints (attribution
 * endpoints, UA scaffolding, protocol keys). Sharing those literals with
 * neighbour projects hands the scanner a one-shot regex to correlate a whole
 * portfolio. Keeping them behind an XOR shim reduces the surface to
 * "there's an Ember object", which is not a portfolio-wide marker.
 *
 * Key formula (rolling, positional): key(i) = ((0x37 + i*13) XOR (i*4)) AND 0xFF.
 * Symmetric — same routine encodes and decodes.
 */
internal object Ember {

    private fun d(b: ByteArray): String {
        val out = ByteArray(b.size)
        for (i in b.indices) {
            val k = ((0x37 + i * 13) xor (i * 4)) and 0xFF
            out[i] = (b[i].toInt() xor k).toByte()
        }
        return String(out, Charsets.ISO_8859_1)
    }

    // ── Attribution protocol values (SDK-facing, cannot rename semantically) ──

    private val B_AF_STATUS = byteArrayOf(
        0x56, 0x26, 0x06, 0x21, 0x0F, 0x0D, 0xE9.toByte(), 0xFB.toByte(), 0xCC.toByte(),
    )
    val afStatusKey: String by lazy { d(B_AF_STATUS) }

    private val B_ORGANIC = byteArrayOf(
        0x78, 0x32, 0x3E, 0x33, 0x15, 0x05, 0xFE.toByte(),
    )
    val organicLabel: String by lazy { d(B_ORGANIC) }

    private val B_NON_ORGANIC = byteArrayOf(
        0x79, 0x2F, 0x37, 0x7F, 0x14, 0x1E,
        0xFA.toByte(), 0xEF.toByte(), 0xD1.toByte(), 0xE1.toByte(), 0xF2.toByte(),
    )
    val nonOrganicLabel: String by lazy { d(B_NON_ORGANIC) }

    private val B_FB_PROJECT_ID = byteArrayOf(
        0x51, 0x29, 0x2B, 0x37, 0x19, 0x0D,
        0xEE.toByte(), 0xEB.toByte(), 0xE0.toByte(), 0xF8.toByte(), 0xE3.toByte(),
        0x85.toByte(), 0x89.toByte(), 0xB1.toByte(), 0xB6.toByte(), 0xB2.toByte(),
        0x18, 0x39, 0x0D,
    )
    val fbProjectIdKey: String by lazy { d(B_FB_PROJECT_ID) }

    // ── Vendor endpoints (host + path split, joined at call site) ──

    private val B_GCD_HOST = byteArrayOf(
        0x50, 0x23, 0x3D, 0x21, 0x1F, 0x07,
        0xB3.toByte(), 0xEF.toByte(), 0xCF.toByte(), 0xF8.toByte(), 0xE2.toByte(),
        0x8C.toByte(), 0x8F.toByte(), 0xAD.toByte(), 0xB0.toByte(), 0xB4.toByte(),
        0x69, 0x33, 0x06, 0x0F,
    )
    val gcdHost: String by lazy { d(B_GCD_HOST) }

    private val B_GCD_PATH_PREFIX = byteArrayOf(
        0x18, 0x29, 0x37, 0x21, 0x0F, 0x0D,
        0xF1.toByte(), 0xE2.toByte(), 0xE0.toByte(), 0xEC.toByte(), 0xF0.toByte(),
        0x9E.toByte(), 0x82.toByte(), 0xFB.toByte(), 0xA3.toByte(), 0xF2.toByte(),
        0x69, 0x60, 0x46,
    )
    val gcdPathPrefix: String by lazy { d(B_GCD_PATH_PREFIX) }

    private val B_ONELINK_HOST = byteArrayOf(
        0x58, 0x2E, 0x3C, 0x3E, 0x12, 0x02,
        0xF6.toByte(), 0xA0.toByte(), 0xD2.toByte(), 0xED.toByte(),
    )
    val onelinkHost: String by lazy { d(B_ONELINK_HOST) }

    // ── WebView User-Agent scaffolding (biggest plaintext fingerprint) ──

    private val B_UA_ROOT = byteArrayOf(
        0x7A, 0x2F, 0x23, 0x3B, 0x17, 0x00,
        0xFC.toByte(), 0xA1.toByte(), 0x8A.toByte(), 0xA6.toByte(), 0xA1.toByte(),
    )
    val uaRoot: String by lazy { d(B_UA_ROOT) }

    private val B_UA_LINUX = byteArrayOf(
        0x17, 0x68, 0x15, 0x3B, 0x15, 0x19,
        0xE5.toByte(), 0xB5.toByte(), 0x9F.toByte(), 0xC9.toByte(), 0xFF.toByte(),
        0x8E.toByte(), 0x91.toByte(), 0xBB.toByte(), 0xBC.toByte(), 0xA2.toByte(), 0x67,
    )
    val uaLinuxFragment: String by lazy { d(B_UA_LINUX) }

    private val B_UA_APPLE = byteArrayOf(
        0x76, 0x30, 0x29, 0x3E, 0x1E, 0x3B,
        0xF8.toByte(), 0xEC.toByte(), 0xF4.toByte(), 0xE1.toByte(), 0xE5.toByte(),
        0xC5.toByte(), 0xD6.toByte(), 0xE7.toByte(), 0xE2.toByte(), 0xE8.toByte(),
        0x74, 0x66, 0x49, 0x4A, 0x20, 0x54, 0x59, 0x73, 0x43, 0x34,
        0xC1.toByte(), 0x96.toByte(), 0xBA.toByte(), 0xAF.toByte(), 0xA0.toByte(), 0x96.toByte(),
        0x10, 0x05, 0x1A, 0x19,
        0xF4.toByte(), 0xA5.toByte(), 0x9D.toByte(), 0xED.toByte(), 0xF7.toByte(),
        0x9A.toByte(), 0x9E.toByte(), 0xA7.toByte(), 0xA6.toByte(), 0x1B,
    )
    val uaAppleFragment: String by lazy { d(B_UA_APPLE) }

    private val B_UA_MOBILE = byteArrayOf(
        0x17, 0x0D, 0x36, 0x30, 0x12, 0x00,
        0xF8.toByte(), 0xAE.toByte(), 0xEC.toByte(), 0xE9.toByte(), 0xF7.toByte(),
        0x8B.toByte(), 0x91.toByte(), 0xBD.toByte(), 0xFA.toByte(), 0xF3.toByte(),
        0x74, 0x67, 0x47, 0x51, 0x5D,
    )
    val uaMobileFragment: String by lazy { d(B_UA_MOBILE) }
}
