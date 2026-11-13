package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The journal a report reads after the process that failed is gone (#606). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WheelKeyJournalTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val base = 1_700_000_000_000L

    @Before fun setUp() = WheelKeyJournal.reset(context)

    @After fun tearDown() = WheelKeyJournal.reset(context)

    private fun events(): List<String> = WheelKeyJournal.report(context).drop(1)
        .map { it.substringAfter("Wheel key journal: ").substringAfter(' ') }

    @Test fun nothingRecordedSaysSoInsteadOfPrintingAnEmptySection() {
        assertEquals(listOf("Wheel key service journal: nothing recorded yet (sessions=0)"),
            WheelKeyJournal.report(context))
    }

    @Test fun anEntryOutlivesTheProcessThatWroteIt() {
        WheelKeyJournal.append(context, "service connected", nowMillis = base)
        // A new app run keeps the stored lines; only the in-memory flag starts over.
        WheelKeyJournal.resetProcessFlagForTest()
        WheelKeyJournal.append(context, "repair stopped: the service list could not be written", nowMillis = base + 1000)
        val report = WheelKeyJournal.report(context)
        assertTrue(report.first(), report.first().startsWith("Wheel key service journal: sessions=2 thisSessionMs="))
        assertTrue(report.first(), report.first().endsWith("entriesKept=2 newestLast"))
        assertEquals(
            listOf("service connected", "repair stopped: the service list could not be written"),
            events(),
        )
    }

    @Test fun theNewestEntriesAreKeptAndTheOldestAreDropped() {
        for (i in 1..(WheelKeyJournal.ENTRIES + 5)) {
            WheelKeyJournal.append(context, "attempt $i", nowMillis = base + i * 1_000L)
        }
        val report = WheelKeyJournal.report(context)
        assertTrue(report.first(), report.first().endsWith("entriesKept=${WheelKeyJournal.ENTRIES} newestLast"))
        assertEquals("${WheelKeyJournal.ENTRIES + 5}", events().last().substringAfter("attempt "))
        assertEquals("6", events().first().substringAfter("attempt "))
    }

    @Test fun linesThatCarryAKeyOrACredentialAreLeftOut() {
        WheelKeyJournal.append(context, "repair stopped: the service list could not be read", nowMillis = base)
        WheelKeyJournal.append(context, "token=abc", nowMillis = base + 1000)
        assertEquals(listOf("repair stopped: the service list could not be read"), events())
    }
}
