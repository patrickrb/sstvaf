package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for Yaesu gen-2 (FT-817/857/897) command constants and the
 * BCD frequency encoder. The 5-byte CAT block is 4 BCD frequency bytes (in
 * 10-Hz-ish nibble weights) + a 1-byte opcode/mode trailer.
 */
public class Yaesu2RigConstantTest {

    @Test
    public void getModeStr_mapsKnownModes() {
        assertThat(Yaesu2RigConstant.getModeStr(Yaesu2RigConstant.LSB)).isEqualTo("LSB");
        assertThat(Yaesu2RigConstant.getModeStr(Yaesu2RigConstant.USB)).isEqualTo("USB");
        assertThat(Yaesu2RigConstant.getModeStr(Yaesu2RigConstant.DIG)).isEqualTo("DIG");
        assertThat(Yaesu2RigConstant.getModeStr(Yaesu2RigConstant.PKT)).isEqualTo("PKT");
    }

    @Test
    public void getModeStr_unknownFallsBack() {
        assertThat(Yaesu2RigConstant.getModeStr(0x55)).isEqualTo("UNKNOWN");
    }

    @Test
    public void setOperationFreq_encodesBcdWithTrailer() {
        // 14.074 MHz -> 01 40 74 00 + opcode 01.
        byte[] f = Yaesu2RigConstant.setOperationFreq(14_074_000L);
        byte[] expected = {(byte) 0x01, (byte) 0x40, (byte) 0x74, (byte) 0x00, (byte) 0x01};
        assertThat(f).isEqualTo(expected);
    }

    @Test
    public void setOperationFreq_packsTensOfHzInLastNibble() {
        // 14.074050 MHz: the 50 Hz component is the tens-of-Hz digit (5), which must
        // land in the low nibble of the last byte -> 0x05, not the raw remainder 50
        // (0x32). Packing 50 there used to desync the encoder from getFrequency, which
        // weights that nibble x10 (would have read back 14_074_320, +270 Hz).
        byte[] f = Yaesu2RigConstant.setOperationFreq(14_074_050L);
        byte[] expected = {(byte) 0x01, (byte) 0x40, (byte) 0x74, (byte) 0x05, (byte) 0x01};
        assertThat(f).isEqualTo(expected);
    }

    @Test
    public void setPTTState_toggleByteDiffers() {
        byte[] on = Yaesu2RigConstant.setPTTState(true);
        byte[] off = Yaesu2RigConstant.setPTTState(false);
        assertThat(on).hasLength(5);
        assertThat(on[4] & 0xFF).isEqualTo(0x08);
        assertThat(off[4] & 0xFF).isEqualTo(0x88);
    }

    @Test
    public void modeCommands_carryExpectedOpcodes() {
        // DIG mode used for the generic USB-data setter; opcode 07.
        byte[] dig = Yaesu2RigConstant.setOperationUSBMode();
        assertThat(dig[0] & 0xFF).isEqualTo(0x0A);
        assertThat(dig[4] & 0xFF).isEqualTo(0x07);

        // The 847 USB setter uses USB mode byte 0x01.
        byte[] usb = Yaesu2RigConstant.setOperationUSB847Mode();
        assertThat(usb[0] & 0xFF).isEqualTo(0x01);
        assertThat(usb[4] & 0xFF).isEqualTo(0x07);
    }

    @Test
    public void readMeterAndFreqCommands_haveExpectedOpcodes() {
        assertThat(Yaesu2RigConstant.readMeter()[4] & 0xFF).isEqualTo(0xBD);
        assertThat(Yaesu2RigConstant.setReadOperationFreq()[4] & 0xFF).isEqualTo(0x03);
    }

    @Test
    public void connectAndDisconnectCommands_haveExpectedOpcodes() {
        assertThat(Yaesu2RigConstant.sendConnectData()[4] & 0xFF).isEqualTo(0x00);
        assertThat(Yaesu2RigConstant.sendDisconnectData()[4] & 0xFF).isEqualTo(0x80);
    }

    // ---- normalizeSwr817 ------------------------------------------------------
    // Expected values are the hamlib FT817_SWR_CAL ratios (measured by WA4YA/DL4YA)
    // pushed through MeterProtectionController.swrRatioToNormalized.

    @Test
    public void normalizeSwr817_negativeMeansNoReading() {
        assertThat(Yaesu2RigConstant.normalizeSwr817(-1)).isEqualTo(-1);
    }

    @Test
    public void normalizeSwr817_zeroIsPerfectMatch() {
        // Direct scale: raw 0 = 1.0:1 -> normalized 0 (NOT a high-SWR reading).
        assertThat(Yaesu2RigConstant.normalizeSwr817(0)).isEqualTo(0);
    }

    @Test
    public void normalizeSwr817_straddlesDefaultHaltThreshold() {
        // Raw 4 = 2.25:1 -> 90, below the default 120 (~3:1) halt threshold;
        // raw 5 = 3.7:1 -> 134, above it. The old linear nibble*17 put raw 5 at 85
        // and did not cross 120 until raw 8 (~8:1 actual) — protection far too late.
        assertThat(Yaesu2RigConstant.normalizeSwr817(4)).isEqualTo(90);
        assertThat(Yaesu2RigConstant.normalizeSwr817(5)).isEqualTo(134);
    }

    @Test
    public void normalizeSwr817_upperScale() {
        // Raw 7 = 7.0:1 -> 200; raw 10+ saturates at 10:1 -> 255.
        assertThat(Yaesu2RigConstant.normalizeSwr817(7)).isEqualTo(200);
        assertThat(Yaesu2RigConstant.normalizeSwr817(10)).isEqualTo(255);
        assertThat(Yaesu2RigConstant.normalizeSwr817(15)).isEqualTo(255);
    }

    @Test
    public void normalizeSwr817_clampsAboveNibbleRange() {
        // Defensive: values past the 4-bit range clamp to the saturated top entry.
        assertThat(Yaesu2RigConstant.normalizeSwr817(20)).isEqualTo(255);
    }
}
