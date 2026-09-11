package com.plum0.jabralibre

/**
 * Battery state as reported by topic `1202` (reply type `d1`, push type `11`).
 *
 * Observed record (Elite 10, firmware 4.6.0):
 *
 *     80 5a 00 00 02 | 15 00 50 | 05 00 55
 *     ^  ^     ^  ^    ^^ ^^ ^^
 *     |  |     |  |    one entry per component: id, flags, percent
 *     |  |     |  count of entries that follow
 *     |  level of the reporting (primary) bud
 *     flags — 0x80 seen whenever a level is present
 *
 * Component ids match the ones topic `0228` enumerates (`04 05 15`), where
 * index `05` returned the primary bud's Bluetooth address and index `15`
 * returned all zeros — which is why `15` is read as the charging case.
 */
data class Battery(
    val left: Int?,     // component 04 — reported in the record header
    val right: Int?,    // component 05 — the bud the phone is bonded to
    val case: Int?      // component 15 — no Bluetooth address of its own
) {
    val isEmpty: Boolean get() = left == null && right == null && case == null

    companion object {
        private const val ID_RIGHT = 0x05
        private const val ID_CASE = 0x15

        /** `payload` is everything after the two topic bytes. */
        fun parse(payload: ByteArray): Battery? {
            if (payload.size < 5) return null
            fun pct(b: Byte): Int? = (b.toInt() and 0xff).takeIf { it in 0..100 }

            val left = pct(payload[1])
            var right: Int? = null
            var case: Int? = null

            val count = payload[4].toInt() and 0xff
            var at = 5
            repeat(count) {
                if (at + 2 > payload.lastIndex) return@repeat
                val level = pct(payload[at + 2])
                when (payload[at].toInt() and 0xff) {
                    ID_RIGHT -> right = level
                    ID_CASE -> case = level
                }
                at += 3
            }
            val b = Battery(left, right, case)
            return if (b.isEmpty) null else b
        }
    }
}
