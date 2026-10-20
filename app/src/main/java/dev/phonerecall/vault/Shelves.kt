package dev.phonerecall.vault

/**
 * Calypso's two shelves. Sealed is the drop box (agents deposit, only his fingerprint opens an item);
 * Shielded is the old Aegis (agents read in the background, logged; people need his fingerprint).
 * Only the screen is shared: stores, keys, tools and log identifiers stay "calypso" and "aegis".
 */
enum class Shelf(val word: String, val about: String, val vault: String) {
    SEALED("Sealed", "Only you can open. Agents can only drop things in.", Calypso.CALYPSO),
    SHIELDED("Shielded", "Agents can use it, every use logged. People need your fingerprint.", Aegis.AEGIS);

    companion object {
        /**
         * The shelf a destination name opens, or null when it is not the vault. The old Aegis destination
         * (from saved state, notifications or widgets) lands on Shielded.
         */
        fun forDest(dest: String): Shelf? = when (dest.uppercase()) {
            "VAULT", "CALYPSO", "SEALED" -> SEALED
            "SHIELD", "AEGIS", "SHIELDED" -> SHIELDED
            else -> null
        }

        fun of(row: VaultLogRow): Shelf = if (row.vault == Aegis.AEGIS) SHIELDED else SEALED
    }
}

/**
 * One log for both shelves, newest first. A shelf passes null while it is locked, so its rows stay
 * hidden exactly as before the merge.
 */
fun mergedLog(sealed: List<VaultLogRow>?, shielded: List<VaultLogRow>?): List<VaultLogRow> =
    (sealed.orEmpty() + shielded.orEmpty()).sortedByDescending { it.at }

/** Stored actions keep the old names; the screen says shelves. */
fun shelfAction(action: String): String = when (action) {
    "moved to Aegis" -> "moved to Shielded"
    "moved to Calypso" -> "moved to Sealed"
    else -> action
}
