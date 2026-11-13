package com.k1af.ft8af.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.database.DatabaseOpr
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Golden-file test for the ADIF export path (ShareLogs.downQSLTableToFile):
 * two QSOs are inserted through the real engine write path
 * (DatabaseOpr.doInsertQSLData) and the exported .adi must match
 * `src/test/resources/golden/sstv-export.adi` byte for byte (modulo CRLF
 * normalization, in case the golden file is checked out with Windows line
 * endings).
 *
 * What the golden pins down:
 *  - the SSTVAF export header,
 *  - an SSTV QSO exporting `<mode:4>SSTV` plus `<SUBMODE:9>Scottie 1`
 *    (ADIF 3.x: MODE=SSTV, SUBMODE carries the SSTV mode name),
 *  - RSV reports as plain three-digit values (595, not +595),
 *  - a legacy FT8-style record staying byte-identical (signed SNR reports,
 *    no SUBMODE field).
 */
@RunWith(RobolectricTestRunner::class)
class ShareLogsAdifGoldenTest {

    private lateinit var context: Context
    private lateinit var opr: DatabaseOpr
    private lateinit var adiFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        opr = DatabaseOpr(context, null, null, 20) // in-memory, current schema
        adiFile = File.createTempFile("sstv-export-test", ".adi")
    }

    @After
    fun tearDown() {
        opr.close()
        adiFile.delete()
    }

    private fun goldenText(): String {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("golden/sstv-export.adi"),
        ) { "golden/sstv-export.adi missing from test resources" }
        return stream.bufferedReader().use { it.readText() }.replace("\r\n", "\n")
    }

    @Test
    fun export_matchesGoldenFile_includingSstvModeAndSubmode() {
        // Legacy FT8-style record (signed SNR reports, no grids, no submode).
        val ft8 = QSLRecord(
            0L, 0L, "KS3CKC", "", "W1AW", "",
            -5, -10, "FT8", 14_074_000L, 1500,
        )
        ft8.setComment("legacy")
        assertThat(opr.doInsertQSLData(ft8, null)).isTrue()

        // Manual SSTV QSO: mode SSTV, submode Scottie 1, RSV 595 both ways.
        val sstv = QSLRecord(
            0L, 0L, "KS3CKC", "EM28", "TEST1", "EM29",
            595, 595, "SSTV", 14_230_000L, 0,
        )
        sstv.setSubmode("Scottie 1")
        sstv.setComment("First SSTV contact")
        assertThat(opr.doInsertQSLData(sstv, null)).isTrue()

        ShareLogs().downQSLTableToFile(opr.db, "", 0, null, null, adiFile, false, null)

        assertThat(adiFile.readText()).isEqualTo(goldenText())
    }

    @Test
    fun export_sstvRecordCarriesModeAndSubmodeFields() {
        // Belt and braces on top of the byte-exact golden: the two fields the
        // ADIF spec cares about for SSTV are present in the right shape.
        val sstv = QSLRecord(
            0L, 0L, "KS3CKC", "EM28", "TEST1", "EM29",
            595, 595, "SSTV", 14_230_000L, 0,
        )
        sstv.setSubmode("Robot 36")
        opr.doInsertQSLData(sstv, null)

        ShareLogs().downQSLTableToFile(opr.db, "", 0, null, null, adiFile, false, null)
        val text = adiFile.readText()

        assertThat(text).contains("<mode:4>SSTV ")
        assertThat(text).contains("<SUBMODE:8>Robot 36 ")
        assertThat(text).contains("<rst_sent:3>595 ")
        assertThat(text).contains("<rst_rcvd:3>595 ")
    }
}
