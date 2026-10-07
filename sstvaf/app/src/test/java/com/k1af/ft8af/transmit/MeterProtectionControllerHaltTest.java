package com.k1af.ft8af.transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.GeneralVariables;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Stateful coverage of the debounced SWR halt path through
 * {@link MeterProtectionController#onMeterUpdate(int, int)}: a halt requires
 * {@link MeterProtectionController#SWR_HALT_CONSECUTIVE_READINGS} consecutive
 * over-threshold readings, so a single key-down transient (the first in-TX meter
 * poll lands ~2s after key-down) can no longer cancel a multi-minute image
 * (issue #97). Robolectric because onMeterUpdate posts to LiveData.
 */
@RunWith(RobolectricTestRunner.class)
public class MeterProtectionControllerHaltTest {

    private boolean savedHaltEnabled;
    private int savedThreshold;
    private boolean savedAutoVolume;

    private MeterProtectionController controller;
    private int haltCount;

    @Before
    public void setUp() {
        savedHaltEnabled = GeneralVariables.swrHaltEnabled;
        savedThreshold = GeneralVariables.swrHaltThreshold;
        savedAutoVolume = GeneralVariables.autoVolumeEnabled;
        GeneralVariables.swrHaltEnabled = true;
        GeneralVariables.swrHaltThreshold = 120;
        GeneralVariables.autoVolumeEnabled = false;

        controller = new MeterProtectionController();
        haltCount = 0;
        controller.setOnSwrHalt(() -> haltCount++);
    }

    @After
    public void tearDown() {
        GeneralVariables.swrHaltEnabled = savedHaltEnabled;
        GeneralVariables.swrHaltThreshold = savedThreshold;
        GeneralVariables.autoVolumeEnabled = savedAutoVolume;
    }

    @Test
    public void singleOverThresholdReading_doesNotHalt() {
        controller.onMeterUpdate(100, 150);
        assertThat(haltCount).isEqualTo(0);
    }

    @Test
    public void consecutiveOverThresholdReadings_halt() {
        controller.onMeterUpdate(100, 150);
        controller.onMeterUpdate(100, 150);
        assertThat(haltCount).isEqualTo(1);
    }

    @Test
    public void underThresholdReadingBetween_breaksTheStreak() {
        controller.onMeterUpdate(100, 150);
        controller.onMeterUpdate(100, 90);   // healthy reading resets the debounce
        controller.onMeterUpdate(100, 150);
        assertThat(haltCount).isEqualTo(0);
    }

    @Test
    public void alcOnlyUpdateBetween_doesNotBreakTheStreak() {
        controller.onMeterUpdate(100, 150);
        controller.onMeterUpdate(100, -1);   // ALC-only update carries no SWR verdict
        controller.onMeterUpdate(100, 150);
        assertThat(haltCount).isEqualTo(1);
    }

    @Test
    public void streakDoesNotSpanTxCycles() {
        controller.onMeterUpdate(100, 150);
        controller.onTxCycleEnd();           // image A ended with one high reading
        controller.onMeterUpdate(100, 150);  // image B's first reading must not halt
        assertThat(haltCount).isEqualTo(0);
        controller.onMeterUpdate(100, 150);  // ...but a confirmed fault in B still does
        assertThat(haltCount).isEqualTo(1);
    }

    @Test
    public void resetClearsTheStreak() {
        controller.onMeterUpdate(100, 150);
        controller.reset();
        controller.onMeterUpdate(100, 150);
        assertThat(haltCount).isEqualTo(0);
    }

    @Test
    public void disabledProtection_neverHalts() {
        GeneralVariables.swrHaltEnabled = false;
        controller.onMeterUpdate(100, 200);
        controller.onMeterUpdate(100, 200);
        assertThat(haltCount).isEqualTo(0);
    }
}
