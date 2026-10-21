package dev.phonerecall.qol

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.phonerecall.R
import dev.phonerecall.clio.Clio
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentsCore
import dev.phonerecall.odyssey.DriveReport
import dev.phonerecall.odyssey.FillUp

/**
 * Gas on the way: when a drive starts and the tank is probably low (the car's app said so in the last day, or
 * Odyssey counts about 280 miles since the last fill-up), one quiet notification with a Maps search for gas.
 */
object Gas {
    private const val ID = 61_580
    const val LOW_MILES = 280
    const val FIND = "geo:0,0?q=gas+station"
    private val LOW = Regex("""(?i)\b(low fuel|fuel (level )?(is )?low|low on (fuel|gas)|refuel soon|fuel warning)\b""")

    /** Miles driven since the last fill-up Odyssey knows of; null with none. */
    fun milesSince(fills: List<FillUp>, drives: List<DriveReport>): Int? {
        val last = fills.filter { it.state != "skipped" }.maxOfOrNull { it.at } ?: return null
        return (drives.filter { it.start > last }.sumOf { it.meters } / 1609.34).toInt()
    }

    fun lowText(text: String) = LOW.containsMatchIn(text)

    fun low(miles: Int?, warnedAt: Long, now: Long): Boolean = (miles != null && miles >= LOW_MILES) || now - warnedAt in 0..86_400_000L

    /** A drive started. At most once a day; never on a call. */
    fun driveStarted(c: Context) {
        if (MomentKind.GAS in MomentsCore.load(c).never || Quiet.onCall(c)) return
        val d = dev.phonerecall.odyssey.Odyssey.state(c)
        val now = System.currentTimeMillis()
        val st = QolCore.load(c)
        val miles = milesSince(d.car.fillups, d.drives)
        if (!low(miles, st.lowFuelAt, now) || now - st.gasNudged < 20 * 3_600_000L) return
        QolCore.update(c) { it.copy(gasNudged = now) }
        val nm = QolCore.channel(c) ?: return
        val find = Opens.intent(c, FIND)?.let { PendingIntent.getActivity(c, ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) } ?: return
        nm.notify(ID, NotificationCompat.Builder(c, "qol").setSmallIcon(R.drawable.ic_owl)
            .setContentTitle("Gas on your route?").setContentText(miles?.let { "About $it mi since the last fill-up" } ?: "The car says fuel is low")
            .setContentIntent(find).addAction(0, "Find gas", find).setAutoCancel(true).setSilent(true).setTimeoutAfter(45 * 60_000L).build())
        Clio.info(QolCore.AREA, "gas nudge", miles?.let { "$it mi since fill-up" } ?: "car app said low")
    }
}
