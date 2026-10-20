package dev.phonerecall.calls

import java.time.ZoneId

/**
 * How a call goes out when the owner doesn't say (ported from PhoneRecall's CallDefaults): by the number's
 * country. India (+91) on WhatsApp, video first; US/Canada (+1) and everything else as a phone call.
 * Explicit words ("phone call", "whatsapp call", "video call") always win. Pure, so it is tested as is.
 */
object CallRouting {
    const val VIDEO = "whatsapp_video"
    const val VOICE = "whatsapp_voice"
    const val PHONE = "phone"

    val COUNTRIES = linkedMapOf("IN" to "India (+91)", "US" to "US and Canada (+1)", "OTHER" to "Other countries")
    private val DEFAULTS = mapOf("IN" to VIDEO, "US" to PHONE, "OTHER" to PHONE)

    /** India's one zone. Never a fixed offset: "8 AM IST" is 10:30 PM in EDT and 9:30 PM in EST. */
    val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")

    fun method(country: String): String = DEFAULTS[country] ?: PHONE

    /** "video", "whatsapp", "voice", "phone call" and the wire words, to one of [VIDEO], [VOICE], [PHONE]; null for auto. */
    fun via(word: String?): String? = when (word?.trim()?.lowercase()?.replace(' ', '_')) {
        null, "", "auto" -> null
        VIDEO, "video", "whatsapp_video_call", "video_call" -> VIDEO
        VOICE, "voice", "whatsapp", "whatsapp_call", "whatsapp_voice_call", "voice_call" -> VOICE
        PHONE, "phone_call", "call", "cell", "regular" -> PHONE
        else -> null
    }

    /**
     * "IN", "US" or "OTHER" for a number as stored in contacts. International forms decide directly; a local
     * number is read in the phone's own [region] (its E.164 form when Android could make one). A bare 10-digit
     * number is never guessed as Indian, because US area codes start with 6 to 9 too (716 Buffalo).
     */
    fun country(number: String, region: String, e164: String? = null): String {
        val digits = number.filter { it.isDigit() }
        val plus = number.trim().startsWith("+")
        when {
            (plus && digits.startsWith("91")) || digits.startsWith("0091") -> return "IN"
            !plus && digits.length == 12 && digits.startsWith("91") && digits[2] in '6'..'9' -> return "IN"
            (plus && digits.startsWith("1")) || (digits.length == 11 && digits.startsWith("1")) -> return "US"
        }
        val e = e164 ?: return if (region == "IN") "IN" else if (region in setOf("US", "CA")) "US" else "OTHER"
        return when { e.startsWith("+91") -> "IN"; e.startsWith("+1") -> "US"; else -> "OTHER" }
    }

    /** A bare 10-digit number matched against the contact's WhatsApp number: 91+digits is India, 1+digits is US. */
    fun viaWhatsapp(digits: String, wa: String?): String? = when {
        wa == null -> null
        wa == "91$digits" || (wa.startsWith("91") && wa.endsWith(digits)) -> "IN"
        wa == "1$digits" || (wa.startsWith("1") && wa.endsWith(digits)) -> "US"
        else -> null
    }

    /** The country for [number] and whether WhatsApp's own number decided it (the raw_contacts SYNC1 JID). */
    fun countryFor(number: String, wa: String?, region: String, e164: String? = null): Pair<String, Boolean> {
        val digits = number.filter { it.isDigit() }
        if (!number.trim().startsWith("+") && digits.length == 10 && digits[0] in '6'..'9') {
            viaWhatsapp(digits, wa)?.let { return it to true }
        }
        return country(number, region, e164) to false
    }

    /** "919876543210@s.whatsapp.net" to its digits; null when it is not a phone JID. */
    fun jidDigits(jid: String?): String? = jid?.substringBefore('@')?.filter(Char::isDigit)?.takeIf { it.length >= 10 }

    /** The person's zone: India for Indian numbers, otherwise the phone's own. */
    fun zoneFor(country: String?, local: ZoneId): ZoneId = if (country == "IN") INDIA else local

    /** How well a contact name answers "call amma": 1 for the same name, down to 0. */
    fun matchScore(query: String, name: String): Float {
        val q = norm(query); val n = norm(name)
        if (q.isEmpty() || n.isEmpty()) return 0f
        if (q == n) return 1f
        val words = n.split(' ')
        if (q in words) return 0.92f
        if (n.startsWith(q) || words.any { it.startsWith(q) && q.length >= 3 }) return 0.85f
        val d = distance(q, n)
        return (1f - d.toFloat() / maxOf(q.length, n.length)).coerceAtLeast(0f)
    }

    private fun norm(s: String) = s.lowercase().removePrefix("my ").replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
