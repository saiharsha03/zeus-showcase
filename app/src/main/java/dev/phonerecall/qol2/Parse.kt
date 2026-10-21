package dev.phonerecall.qol2

/** Text the touches read on the phone. Pure; nothing here leaves the phone. */
object TouchParse {
    private val CANCELLED = Regex("(?i)\\b(cancel+ed|cancel+ation (?:is )?confirmed|cancel+ation confirmation|you(?:'ve| have) cancel+ed|has been cancel+ed|won't renew|will not renew)\\b")
    private val SUBSCRIPTION = Regex("(?i)\\b(subscription|membership|plan|trial|auto-?renew)")
    /** "Your Netflix membership", "your YouTube Premium subscription": capitalised words only, so "your request. Your subscription" names nothing. */
    private val SERVICE = Regex("\\b[Yy]our\\s+([A-Z][\\w+&'.]*(?:\\s+[A-Z][\\w+&'.]*){0,2})\\s+(?:[Ss]ubscription|[Mm]embership|[Pp]lan|[Tt]rial)\\b")
    /** Mail apps: their title is the sender ("Netflix"). */
    private val MAIL = setOf("com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider")

    /** A cancellation notice: the service it is for, or null. Order and flight cancellations don't count. */
    fun cancellation(pkg: String, app: String, title: String, text: String): String? {
        val all = "$title\n$text"
        if (!CANCELLED.containsMatchIn(all) || !SUBSCRIPTION.containsMatchIn(all)) return null
        if (Regex("(?i)\\b(order|flight|reservation|booking|appointment)\\b").containsMatchIn(all)) return null
        SERVICE.find(all)?.groupValues?.get(1)?.trim()?.let { return it }
        return if (pkg in MAIL) title.substringBefore('<').trim().ifBlank { null } else app.ifBlank { null }
    }

    private val URL = Regex("https?://[^\\s<>\"')]+")
    private val TICKETS = Regex("(?i)(ticketmaster|livenation|axs\\.com|seatgeek|stubhub|eventbrite|vividseats|tixr|dice\\.fm|tickets?\\.|/tickets?|wallet\\.google|pay\\.google|universe\\.com|etix)")

    /** Ticket and pass links in an event's notes or place. */
    fun ticketLinks(text: String?): List<String> = text?.let { t -> URL.findAll(t).map { it.value.trimEnd('.', ',') }.filter { TICKETS.containsMatchIn(it) }.distinct().toList() }.orEmpty()

    fun firstUrl(text: String?): String? = text?.let { URL.find(it)?.value?.trimEnd('.', ',') }

    private val GARAGE = Regex("(?i)\\b(parking|garage|ramp|parkade|park\\s*&\\s*ride|car park)\\b")

    /** A stay named like a parking garage. */
    fun garage(name: String?): Boolean = name != null && GARAGE.containsMatchIn(name)
}
