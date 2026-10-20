package dev.phonerecall.iaso

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Health Connect records (as Samsung Health writes the Galaxy Watch's data) into Iaso's days, through a fake client. */
class HcMappingTest {
    private fun steps(start: Instant, mins: Long, n: Long, pkg: String = SAMSUNG) =
        StepsRecord(start, off(start), start.plusSeconds(mins * 60), off(start), n, from(pkg))

    private fun sleep(start: Instant, end: Instant, vararg stages: SleepSessionRecord.Stage) =
        SleepSessionRecord(startTime = start, startZoneOffset = off(start), endTime = end, endZoneOffset = off(end), metadata = from(SAMSUNG), stages = stages.toList())

    private fun watch() = FakeHc(
        records = mutableListOf(
            // Today: the watch and Google Fit both counted the same walk. Only one source may count.
            steps(at(0, 8), 30, 3_000), steps(at(0, 12), 30, 2_400), steps(at(0, 8), 60, 4_000, FIT),
            steps(at(1, 9), 60, 9_100),
            ActiveCaloriesBurnedRecord(at(0, 8), off(at(0, 8)), at(0, 9), off(at(0, 8)), Energy.kilocalories(210.0), from(SAMSUNG)),
            // Last night: 11:30 PM to 7:00 AM with 20 minutes awake and one hour deep.
            sleep(
                at(1, 23, 30), at(0, 7),
                SleepSessionRecord.Stage(at(1, 23, 30), at(0, 1), SleepSessionRecord.STAGE_TYPE_LIGHT),
                SleepSessionRecord.Stage(at(0, 1), at(0, 2), SleepSessionRecord.STAGE_TYPE_DEEP),
                SleepSessionRecord.Stage(at(0, 3), at(0, 3, 20), SleepSessionRecord.STAGE_TYPE_AWAKE),
            ),
            // A nap that also ended today loses to the night.
            sleep(at(0, 13), at(0, 13, 25)),
            RestingHeartRateRecord(at(0, 6), off(at(0, 6)), 61, from(SAMSUNG)),
            RestingHeartRateRecord(at(0, 9), off(at(0, 9)), 58, from(SAMSUNG)),
            HeartRateRecord(
                startTime = at(0, 9), startZoneOffset = off(at(0, 9)), endTime = at(0, 10, 30), endZoneOffset = off(at(0, 9)),
                samples = listOf(HeartRateRecord.Sample(at(0, 9, 5), 70), HeartRateRecord.Sample(at(0, 9, 40), 80), HeartRateRecord.Sample(at(0, 10, 10), 100)),
                metadata = from(SAMSUNG),
            ),
            ExerciseSessionRecord(
                startTime = at(0, 7, 30), startZoneOffset = off(at(0, 7)), endTime = at(0, 8, 15), endZoneOffset = off(at(0, 7)),
                metadata = from(SAMSUNG), exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            ),
            WeightRecord(at(3, 7), off(at(3, 7)), Mass.kilograms(65.4), from(SAMSUNG)),
            WeightRecord(at(0, 7), off(at(0, 7)), Mass.kilograms(65.0), from("dev.phonerecall")),
        ),
    )

    @Test fun mapsAWatchDay() = runBlocking {
        val r = HcMapping.report(watch(), HcPermissions.ALL, NOW, "dev.phonerecall")
        val today = r.day(NOW.toLocalDate())!!
        // 5,400 from the watch beats Fit's 4,000; never 9,400.
        assertEquals(5_400L, today.steps)
        assertEquals(9_100L, r.day(NOW.toLocalDate().minusDays(1))!!.steps)
        assertEquals(210.0, today.activeKcal!!, 0.01)
        assertEquals(58L, today.restingHr)
        assertEquals(83L, today.avgHr)
        assertEquals(45L, today.exerciseMin)
        assertEquals(7, r.days.size)
        assertNull(r.day(NOW.toLocalDate().minusDays(5))!!.steps)

        val night = r.lastNight(NOW.toLocalDate())!!
        assertEquals(450L - 20L, night.asleepMin)
        assertEquals(60L, night.deepMin)
        assertNull(night.remMin)

        assertEquals(listOf(75L, 100L), r.todayHr.map { it.bpm })
        assertEquals(listOf("Walk"), r.workoutsToday.map { it.name })
        assertEquals(listOf(65.4, 65.0), r.weights.map { it.kg })
        assertEquals(listOf(false, true), r.weights.map { it.mine })
    }

    @Test fun readsOnlyWhatIsAllowed() = runBlocking {
        val hc = watch()
        val r = HcMapping.report(hc, setOf(HealthPermission.getReadPermission(StepsRecord::class)), NOW, "dev.phonerecall")
        assertEquals(listOf(StepsRecord::class), hc.reads.distinct())
        assertEquals(5_400L, r.day(NOW.toLocalDate())!!.steps)
        assertTrue(r.sleep.isEmpty() && r.weights.isEmpty())
    }

    @Test fun notInstalledAndNotPermittedAreStatesNotErrors() = runBlocking {
        val missing = hub(hc = null, sdk = HcStatus.NOT_INSTALLED)
        missing.refresh()
        assertEquals(HcStatus.NOT_INSTALLED, missing.state.value.status)
        assertNull(missing.state.value.error)

        val old = hub(hc = null, sdk = HcStatus.UPDATE_NEEDED)
        old.refresh()
        assertEquals(HcStatus.UPDATE_NEEDED, old.state.value.status)

        val hc = watch().apply { granted = emptySet() }
        val denied = hub(hc = hc)
        denied.refresh()
        assertEquals(HcStatus.NOT_PERMITTED, denied.state.value.status)
        assertTrue(hc.reads.isEmpty())

        val some = watch().apply { granted = HcPermissions.READ }
        val partial = hub(hc = some)
        partial.refresh()
        assertEquals(HcStatus.PARTIAL, partial.state.value.status)
        assertEquals(5_400L, partial.state.value.report.day(NOW.toLocalDate())!!.steps)
    }

    @Test fun permissionsAreExactlyTheSevenReadsAndTwoWrites() {
        assertEquals(7, HcPermissions.READ.size)
        assertEquals(
            setOf("android.permission.health.WRITE_WEIGHT", "android.permission.health.WRITE_NUTRITION"),
            HcPermissions.WRITE,
        )
        assertFalse(HcPermissions.ALL.any { "WRITE_STEPS" in it || "READ_NUTRITION" in it })
    }

    @Test fun aWeightIsWrittenAsAManualWeightRecord() = runBlocking {
        val hc = FakeHc()
        val h = hub(hc = hc)
        assertTrue(h.logWeight(64.8))
        val w = hc.inserted.single() as WeightRecord
        assertEquals(64.8, w.weight.inKilograms, 0.001)
        assertEquals(androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_MANUAL_ENTRY, w.metadata.recordingMethod)
        // Not a believable weight: nothing written.
        assertFalse(h.logWeight(3.0))
        assertEquals(1, hc.inserted.size)
    }
}
