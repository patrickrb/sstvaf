package com.k1af.ft8af.transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * The pure start gate for the tune carrier, carried over from the FT8 engine:
 * an active TX wins, then the TX inhibits (SWR lockout, WSPR sub-band), then
 * route support (AudioTrack-only for now).
 */
public class TuneOperatorGateTest {

    @Test
    public void allClear_allowsTune() {
        assertThat(TuneOperator.tuneBlockReason(false, false, false, false, false, false))
                .isEqualTo(TuneOperator.TuneBlockReason.NONE);
    }

    @Test
    public void activeTxWinsOverEverything() {
        assertThat(TuneOperator.tuneBlockReason(true, true, true, true, true, true))
                .isEqualTo(TuneOperator.TuneBlockReason.TX_ACTIVE);
    }

    @Test
    public void swrLockoutBlocks() {
        assertThat(TuneOperator.tuneBlockReason(false, true, false, false, false, false))
                .isEqualTo(TuneOperator.TuneBlockReason.SWR_LOCKED);
    }

    @Test
    public void wsprFrequencyBlocks() {
        assertThat(TuneOperator.tuneBlockReason(false, false, true, false, false, false))
                .isEqualTo(TuneOperator.TuneBlockReason.WSPR_FREQUENCY);
    }

    @Test
    public void unsupportedRoutesBlock() {
        assertThat(TuneOperator.tuneBlockReason(false, false, false, true, false, false))
                .isEqualTo(TuneOperator.TuneBlockReason.UNSUPPORTED_ROUTE);// truSDX CAT audio
        assertThat(TuneOperator.tuneBlockReason(false, false, false, false, true, false))
                .isEqualTo(TuneOperator.TuneBlockReason.UNSUPPORTED_ROUTE);// network rig
        assertThat(TuneOperator.tuneBlockReason(false, false, false, false, false, true))
                .isEqualTo(TuneOperator.TuneBlockReason.UNSUPPORTED_ROUTE);// USB direct
    }

    @Test
    public void severityOrder_swrBeforeWsprBeforeRoute() {
        assertThat(TuneOperator.tuneBlockReason(false, true, true, true, false, false))
                .isEqualTo(TuneOperator.TuneBlockReason.SWR_LOCKED);
        assertThat(TuneOperator.tuneBlockReason(false, false, true, true, false, false))
                .isEqualTo(TuneOperator.TuneBlockReason.WSPR_FREQUENCY);
    }
}
