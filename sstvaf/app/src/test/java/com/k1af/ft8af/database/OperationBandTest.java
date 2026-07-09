package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Pure-logic coverage for OperationBand. The band list is a public static
 * collection, so the lookup/index helpers can be exercised by populating it
 * directly — no Context or assets needed. Each test resets the shared static
 * {@code bandList} first so they don't bleed into each other.
 *
 * <p>Updated for the SSTVAF transformation: bands.txt is a 3-field format
 * ({@code marked:freqHz:waveLength}) with SSTV dial frequencies; the FT8/FT4/
 * FT2 mode tag (4th field) is gone.
 */
public class OperationBandTest {

    @Before
    public void resetBandList() {
        OperationBand.bandList.clear();
    }

    @Test
    public void defaults_are20mSstvCallingFrequency() {
        assertThat(OperationBand.getDefaultBand()).isEqualTo(14_230_000L);
        assertThat(OperationBand.getDefaultWaveLength()).isEqualTo("20m");
    }

    @Test
    public void bandStringConstructor_parsesMarkedFreqAndWavelength() {
        OperationBand.Band b = new OperationBand.Band("*:14230000:20m");
        assertThat(b.marked).isTrue();
        assertThat(b.band).isEqualTo(14_230_000L);
        assertThat(b.waveLength).isEqualTo("20m");
    }

    @Test
    public void bandStringConstructor_unmarkedLine() {
        OperationBand.Band b = new OperationBand.Band(" :7165000:40m");
        assertThat(b.marked).isFalse();
        assertThat(b.band).isEqualTo(7_165_000L);
        assertThat(b.waveLength).isEqualTo("40m");
    }

    @Test
    public void bandInfo_formatsMHzWithWavelength() {
        OperationBand.Band b = new OperationBand.Band(14_230_000L, "20m");
        assertThat(b.getBandInfo()).isEqualTo("  14.230 MHz (20m)");
    }

    @Test
    public void getIndexByFreq_findsExistingBand() {
        OperationBand.bandList.add(new OperationBand.Band(14_230_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(7_171_000L, "40m"));
        assertThat(OperationBand.getIndexByFreq(7_171_000L)).isEqualTo(1);
    }

    @Test
    public void getIndexByFreq_appendsUnknownBand() {
        OperationBand.bandList.add(new OperationBand.Band(14_230_000L, "20m"));
        int idx = OperationBand.getIndexByFreq(21_340_000L);
        assertThat(idx).isEqualTo(1);
        assertThat(OperationBand.bandList).hasSize(2);
        // The wavelength is derived via BaseRigOperation.getMeterFromFreq.
        assertThat(OperationBand.bandList.get(1).waveLength).isEqualTo("15m");
    }

    @Test
    public void getBandFreq_returnsFreqForIndex() {
        OperationBand.bandList.add(new OperationBand.Band(14_230_000L, "20m"));
        assertThat(OperationBand.getBandFreq(0)).isEqualTo(14_230_000L);
    }

    @Test
    public void getBandFreq_outOfRangeIndex_returnsDefault() {
        // an out-of-range index falls back to the 20m default.
        assertThat(OperationBand.getBandFreq(99)).isEqualTo(14_230_000L);
    }

    @Test
    public void getAllWaveLengths_returnsDistinctInFileOrder() {
        OperationBand.bandList.add(new OperationBand.Band(14_230_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(14_233_000L, "20m")); // dup wavelength
        OperationBand.bandList.add(new OperationBand.Band(7_171_000L, "40m"));
        assertThat(OperationBand.getAllWaveLengths()).containsExactly("20m", "40m").inOrder();
    }

    @Test
    public void getLinesFromInputStream_splitsOnDelimiter() {
        InputStream in = new ByteArrayInputStream(
                "a\nb\nc".getBytes(StandardCharsets.UTF_8));
        assertThat(OperationBand.getLinesFromInputStream(in, "\n"))
                .asList().containsExactly("a", "b", "c").inOrder();
    }

    @Test
    public void bandInfo_markedBand_usesAsteriskPrefix() {
        // The marked variant of getBandInfo() (the "*" prefix branch) is distinct
        // from the leading-space unmarked form.
        OperationBand.Band b = new OperationBand.Band("*:14230000:20m");
        assertThat(b.getBandInfo()).isEqualTo("* 14.230 MHz (20m)");
    }

    @Test
    public void staticGetBandInfo_returnsInfoForIndex() {
        OperationBand.bandList.add(new OperationBand.Band(14_230_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(7_171_000L, "40m"));
        assertThat(OperationBand.getBandInfo(1)).isEqualTo("  7.171 MHz (40m)");
    }

    @Test
    public void staticGetBandInfo_outOfRangeIndex_fallsBackToFirst() {
        // index >= size returns bandList.get(0).getBandInfo().
        OperationBand.bandList.add(new OperationBand.Band(14_230_000L, "20m"));
        assertThat(OperationBand.getBandInfo(99)).isEqualTo("  14.230 MHz (20m)");
    }

    @Test
    public void getBandFreq_lastInRangeIndex_returnsThatEntry() {
        OperationBand.bandList.add(new OperationBand.Band(14_230_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(7_171_000L, "40m"));
        assertThat(OperationBand.getBandFreq(1)).isEqualTo(7_171_000L);
    }

    // ---- the shipped SSTV band plan ------------------------------------------

    /** The exact contents of assets/bands.txt (3-field format, SSTV dials). */
    private static final String[] SSTV_BAND_PLAN = {
            " :1890000:160m",
            "*:3845000:80m",
            " :3730000:80m",
            "*:7171000:40m",
            " :7165000:40m",
            "*:14230000:20m",
            " :14233000:20m",
            " :14236000:20m",
            "*:21340000:15m",
            "*:28680000:10m",
            " :28700000:10m",
            " :50680000:6m",
            "*:144500000:2m",
            " :145500000:2m",
            " :430950000:70cm",
    };

    @Test
    public void sstvBandPlan_allLinesParse() {
        java.util.ArrayList<OperationBand.Band> bands =
                OperationBand.parseBandLines(SSTV_BAND_PLAN);
        assertThat(bands).hasSize(15);
    }

    @Test
    public void sstvBandPlan_coversNineDistinctBands() {
        OperationBand.bandList.addAll(OperationBand.parseBandLines(SSTV_BAND_PLAN));
        assertThat(OperationBand.getAllWaveLengths()).containsExactly(
                "160m", "80m", "40m", "20m", "15m", "10m", "6m", "2m", "70cm")
                .inOrder();
    }

    @Test
    public void sstvBandPlan_markedDefaultsPerBand() {
        // Each band has at most one marked (default) dial, and the marked set is
        // exactly the plan's calling frequencies.
        OperationBand.bandList.addAll(OperationBand.parseBandLines(SSTV_BAND_PLAN));
        java.util.Map<String, Long> markedByBand = new java.util.LinkedHashMap<>();
        for (OperationBand.Band b : OperationBand.bandList) {
            if (b.marked) {
                assertThat(markedByBand).doesNotContainKey(b.waveLength);
                markedByBand.put(b.waveLength, b.band);
            }
        }
        assertThat(markedByBand).containsExactly(
                "80m", 3_845_000L,
                "40m", 7_171_000L,
                "20m", 14_230_000L,
                "15m", 21_340_000L,
                "10m", 28_680_000L,
                "2m", 144_500_000L);
    }

    @Test
    public void sstvBandPlan_defaultBandIsInThePlan() {
        OperationBand.bandList.addAll(OperationBand.parseBandLines(SSTV_BAND_PLAN));
        assertThat(OperationBand.getIndexByFreq(OperationBand.getDefaultBand()))
                .isEqualTo(5);// 14230000 is the 6th line of the plan
        // and no new entry was appended by the lookup:
        assertThat(OperationBand.bandList).hasSize(15);
    }

    // ---- isBandLine ---------------------------------------------------------
    // The bands loader must parse only real band entries; comment/blank lines are
    // skipped so a comment containing a colon can't reach Long.parseLong and crash.

    @Test
    public void isBandLine_parsesMarkedAndUnmarkedEntries() {
        assertThat(OperationBand.isBandLine("*:14230000:20m")).isTrue();
        assertThat(OperationBand.isBandLine(" :7165000:40m")).isTrue();
    }

    @Test
    public void isBandLine_skipsCommentsAndBlanks() {
        assertThat(OperationBand.isBandLine("# SSTV dial frequencies")).isFalse();
        assertThat(OperationBand.isBandLine("")).isFalse();
        assertThat(OperationBand.isBandLine("   ")).isFalse();
        assertThat(OperationBand.isBandLine(null)).isFalse();
        // Plain text with no colon is not a band entry.
        assertThat(OperationBand.isBandLine("just a note")).isFalse();
    }

    @Test
    public void isBandLine_skipsCommentEvenWhenItContainsAColon() {
        assertThat(OperationBand.isBandLine(
                "# Es'hail-2 satellite: 2.4 GHz uplink / 10.489 GHz downlink."))
                .isFalse();
    }

    // ---- index bounds hardening --------------------------------------------

    @Test
    public void isValidBandIndex_acceptsInRangeRejectsNegativeAndEnd() {
        OperationBand.bandList.add(new OperationBand.Band(14_230_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(7_171_000L, "40m"));
        assertThat(OperationBand.isValidBandIndex(0)).isTrue();
        assertThat(OperationBand.isValidBandIndex(1)).isTrue();
        assertThat(OperationBand.isValidBandIndex(-1)).isFalse();
        assertThat(OperationBand.isValidBandIndex(2)).isFalse();   // == size (the off-by-one)
        assertThat(OperationBand.isValidBandIndex(99)).isFalse();
    }

    @Test
    public void getBandFreq_indexEqualToSize_returnsDefault() {
        OperationBand.bandList.add(new OperationBand.Band(7_171_000L, "40m"));
        assertThat(OperationBand.getBandFreq(1)).isEqualTo(OperationBand.getDefaultBand());
    }

    @Test
    public void getBandFreq_negativeIndex_returnsDefault() {
        OperationBand.bandList.add(new OperationBand.Band(7_171_000L, "40m"));
        assertThat(OperationBand.getBandFreq(-1)).isEqualTo(OperationBand.getDefaultBand());
    }

    @Test
    public void getBandInfo_emptyList_returnsDefaultInsteadOfCrashing() {
        assertThat(OperationBand.bandList).isEmpty();
        assertThat(OperationBand.getBandInfo(0)).isEqualTo("  14.230 MHz (20m)");
    }

    @Test
    public void getBandInfo_negativeIndex_fallsBackToFirst() {
        OperationBand.bandList.add(new OperationBand.Band(14_230_000L, "20m"));
        assertThat(OperationBand.getBandInfo(-1)).isEqualTo("  14.230 MHz (20m)");
    }

    // ---- parseBandLines -----------------------------------------------------

    @Test
    public void parseBandLines_keepsValidSkipsCommentsAndBlanks() {
        String[] lines = {"*:14230000:20m", "# a comment", "", " :7165000:40m"};
        java.util.ArrayList<OperationBand.Band> bands = OperationBand.parseBandLines(lines);
        assertThat(bands).hasSize(2);
        assertThat(bands.get(0).band).isEqualTo(14_230_000L);
        assertThat(bands.get(1).band).isEqualTo(7_165_000L);
    }

    @Test
    public void parseBandLines_skipsTruncatedLineButKeepsNeighbours() {
        // "20m:" has a colon (so isBandLine accepts it) but splits to a single
        // field, so the Band constructor throws AIOOBE. It must be skipped while
        // the surrounding valid lines still load.
        String[] lines = {"*:14230000:20m", "20m:", " :7165000:40m"};
        java.util.ArrayList<OperationBand.Band> bands = OperationBand.parseBandLines(lines);
        assertThat(bands).hasSize(2);
        assertThat(bands.get(0).waveLength).isEqualTo("20m");
        assertThat(bands.get(1).waveLength).isEqualTo("40m");
    }

    @Test
    public void parseBandLines_skipsNonNumericFrequency() {
        String[] lines = {"*:notanumber:20m", " :7165000:40m"};
        java.util.ArrayList<OperationBand.Band> bands = OperationBand.parseBandLines(lines);
        assertThat(bands).hasSize(1);
        assertThat(bands.get(0).band).isEqualTo(7_165_000L);
    }

    @Test
    public void parseBandLines_nullInput_returnsEmpty() {
        assertThat(OperationBand.parseBandLines(null)).isEmpty();
    }
}
