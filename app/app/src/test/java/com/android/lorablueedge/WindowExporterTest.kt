package com.android.lorablueedge

import com.android.lorablueedge.analytics.FlowWindow
import com.android.lorablueedge.analytics.ReservoirRole
import com.android.lorablueedge.analytics.WindowExporter
import com.android.lorablueedge.analytics.WindowStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowExporterTest {

    @Test
    fun usableWindow_isFormattedWithDotsAndRole() {
        val window = FlowWindow(900_000L, 1_800_000L, -3.5, 0.25, WindowStatus.USABLE)
        assertEquals(
            "1,CISTERN,900000,1800000,-3.5000,0.2500,USABLE,2.5000,1000.0000",
            WindowExporter.toCsvLine(1, ReservoirRole.CISTERN, window, 2.5, 1000.0)
        )
    }

    @Test
    fun windowWithoutSlope_hasEmptyCells() {
        val window = FlowWindow(0L, 900_000L, null, null, WindowStatus.INSUFFICIENT_DATA)
        assertEquals(
            "7,TANK,0,900000,,,INSUFFICIENT_DATA,1.2000,500.0000",
            WindowExporter.toCsvLine(7, ReservoirRole.TANK, window, 1.2, 500.0)
        )
    }
}