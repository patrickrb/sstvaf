package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.log.QSLRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * CRUD coverage for the QSO log tables behind {@link DatabaseOpr}:
 * {@code doInsertQSLData}'s insert/dedup/update paths into QSLTable and
 * QslCallsigns, {@code updateQSLRecord}, {@code deleteQSLByID},
 * {@code setQSLTableIsQSL}, {@code setQSLCallsignIsQSL} and
 * {@code deleteQSLCallsign}.
 *
 * Uses a Robolectric in-memory database (name=null) so no on-disk state is
 * touched. The async mutators (Thread / AsyncTask based) are verified by
 * polling the table until the write lands.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprQslCrudTest {

    /** 2023-11-14 22:13:20 UTC. */
    private static final long START_MS = 1_700_000_000_000L;
    /** 2023-11-14 22:18:20 UTC (start + 5 min). */
    private static final long END_MS = START_MS + 300_000L;

    private DatabaseOpr opr;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null, 21);
        db = opr.getDb();
    }

    @After
    public void tearDown() {
        opr.close();
    }

    /** The standard in-app QSO record the SSTV engine hands to the database. */
    private static QSLRecord newRecord(String toCallsign) {
        return new QSLRecord(START_MS, END_MS, "KS3CKC", "EM28ax",
                toCallsign, "FN42bb", 595, 585, "SSTV", 14_230_000L, 1500);
    }

    /** Reads the single QSLTable row for {@code call} into a column->string map. */
    private Map<String, String> queryQslRow(String call) {
        try (Cursor cursor = db.rawQuery(
                "select * from QSLTable where \"call\"=?", new String[]{call})) {
            assertThat(cursor.moveToFirst()).isTrue();
            Map<String, String> row = new HashMap<>();
            for (String column : cursor.getColumnNames()) {
                row.put(column, cursor.getString(cursor.getColumnIndex(column)));
            }
            return row;
        }
    }

    private int countRows(String table) {
        try (Cursor cursor = db.rawQuery("select count(*) from " + table, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private int qslIdOf(String call) {
        try (Cursor cursor = db.rawQuery(
                "select id from QSLTable where \"call\"=?", new String[]{call})) {
            assertThat(cursor.moveToFirst()).isTrue();
            return cursor.getInt(0);
        }
    }

    private interface Condition {
        boolean met();
    }

    /**
     * Waits for a background write (raw Thread or AsyncTask) to land. Polls
     * instead of idling loopers because DatabaseOpr's mutators run on real
     * background threads under Robolectric's PAUSED looper mode.
     */
    private static void awaitTrue(String what, Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.met()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    // -----------------------------------------------------------------------
    // Insert / round-trip
    // -----------------------------------------------------------------------

    @Test
    public void insert_roundTripsEveryField() {
        QSLRecord record = newRecord("K1AF");
        record.setSubmode("Scottie 1");
        record.setComment("First SSTV contact");
        record.setMySig("POTA");
        record.setMySigInfo("US-1234");
        record.setSig("POTA");
        record.setSigInfo("US-5678");

        assertThat(opr.doInsertQSLData(record, null)).isTrue();

        Map<String, String> row = queryQslRow("K1AF");
        assertThat(row.get("call")).isEqualTo("K1AF");
        assertThat(row.get("gridsquare")).isEqualTo("FN42bb");
        assertThat(row.get("mode")).isEqualTo("SSTV");
        assertThat(row.get("submode")).isEqualTo("Scottie 1");
        // RSV reports >= 100 are stored as plain digits (no "+" sign).
        assertThat(row.get("rst_sent")).isEqualTo("595");
        assertThat(row.get("rst_rcvd")).isEqualTo("585");
        assertThat(row.get("qso_date")).isEqualTo("20231114");
        assertThat(row.get("time_on")).isEqualTo("221320");
        assertThat(row.get("qso_date_off")).isEqualTo("20231114");
        assertThat(row.get("time_off")).isEqualTo("221820");
        assertThat(row.get("band")).isEqualTo("20m");
        assertThat(row.get("freq")).isEqualTo("14.230000");
        assertThat(row.get("station_callsign")).isEqualTo("KS3CKC");
        assertThat(row.get("my_gridsquare")).isEqualTo("EM28ax");
        assertThat(row.get("comment")).isEqualTo("First SSTV contact");
        assertThat(row.get("my_sig")).isEqualTo("POTA");
        assertThat(row.get("my_sig_info")).isEqualTo("US-1234");
        assertThat(row.get("sig")).isEqualTo("POTA");
        assertThat(row.get("sig_info")).isEqualTo("US-5678");
        assertThat(row.get("isQSL")).isEqualTo("0");
        assertThat(row.get("isLotW_import")).isEqualTo("0");
        assertThat(row.get("isLotW_QSL")).isEqualTo("0");
        assertThat(row.get("synced_cloudlog")).isEqualTo("0");
        assertThat(row.get("synced_qrz")).isEqualTo("0");
    }

    @Test
    public void insert_alsoWritesQslCallsignsRow() {
        assertThat(opr.doInsertQSLData(newRecord("K1AF"), null)).isTrue();

        try (Cursor cursor = db.rawQuery("select * from QslCallsigns", null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("callsign")))
                    .isEqualTo("K1AF");
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("grid")))
                    .isEqualTo("FN42bb");
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("mode")))
                    .isEqualTo("SSTV");
            // startTime/finishTime are "yyyyMMdd-HHmmss" composites.
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("startTime")))
                    .isEqualTo("20231114-221320");
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("finishTime")))
                    .isEqualTo("20231114-221820");
            // band is the human string; band_i the raw dial frequency in Hz.
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("band")))
                    .isEqualTo("14.230MHz (20m)");
            assertThat(cursor.getLong(cursor.getColumnIndexOrThrow("band_i")))
                    .isEqualTo(14_230_000L);
            assertThat(cursor.moveToNext()).isFalse();
        }
    }

    @Test
    public void insert_nullCallsign_isRejected() {
        QSLRecord record = new QSLRecord(START_MS, END_MS, "KS3CKC", "EM28ax",
                null, "", 595, 585, "SSTV", 14_230_000L, 1500);
        final boolean[] flags = new boolean[2];
        assertThat(opr.doInsertQSLData(record, (isInvalid, isNewQSL) -> {
            flags[0] = isInvalid;
            flags[1] = isNewQSL;
        })).isFalse();
        assertThat(flags[0]).isTrue();
        assertThat(countRows("QSLTable")).isEqualTo(0);
        assertThat(countRows("QslCallsigns")).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Awkward values
    // -----------------------------------------------------------------------

    @Test
    public void insert_apostrophesAndSqlMetaCharacters_surviveRoundTrip() {
        // Every write in doInsertQSLData is parameterized; values that would
        // break naive string-concatenated SQL must round-trip byte-exact.
        QSLRecord record = newRecord("O'BRIEN");
        record.setSubmode("Martin 1");
        record.setComment("It's fine'); DROP TABLE QSLTable;--");
        record.setMySigInfo("Park's \"ref\" %_?");

        assertThat(opr.doInsertQSLData(record, null)).isTrue();

        Map<String, String> row = queryQslRow("O'BRIEN");
        assertThat(row.get("call")).isEqualTo("O'BRIEN");
        assertThat(row.get("comment")).isEqualTo("It's fine'); DROP TABLE QSLTable;--");
        assertThat(row.get("my_sig_info")).isEqualTo("Park's \"ref\" %_?");

        // Table intact, exactly one row, and the dedup lookup (WHERE call=?)
        // still matches the apostrophe callsign: re-insert must not duplicate.
        assertThat(opr.doInsertQSLData(newRecord("O'BRIEN"), null)).isTrue();
        assertThat(countRows("QSLTable")).isEqualTo(1);
        assertThat(countRows("QslCallsigns")).isEqualTo(1);
    }

    @Test
    public void insert_emptyStrings_surviveRoundTrip() {
        QSLRecord record = new QSLRecord(START_MS, END_MS, "KS3CKC", "",
                "K1AF", "", 595, 585, "SSTV", 14_230_000L, 1500);
        record.setComment("");
        // submode defaults to "" already; make it explicit.
        record.setSubmode("");

        assertThat(opr.doInsertQSLData(record, null)).isTrue();

        Map<String, String> row = queryQslRow("K1AF");
        assertThat(row.get("gridsquare")).isEmpty();
        assertThat(row.get("my_gridsquare")).isEmpty();
        assertThat(row.get("comment")).isEmpty();
        assertThat(row.get("submode")).isEmpty();
    }

    @Test
    public void insert_extremeNumericValues_surviveRoundTrip() {
        // Min/max int reports and a zero dial frequency.
        QSLRecord zeroFreq = new QSLRecord(START_MS, END_MS, "KS3CKC", "EM28",
                "AA1AA", "FN42", Integer.MIN_VALUE, Integer.MAX_VALUE, "SSTV", 0L, 0);
        assertThat(opr.doInsertQSLData(zeroFreq, null)).isTrue();

        Map<String, String> row = queryQslRow("AA1AA");
        // < 100 -> SNR style with sign; >= 100 -> plain digits.
        assertThat(row.get("rst_sent")).isEqualTo("-2147483648");
        assertThat(row.get("rst_rcvd")).isEqualTo("2147483647");
        // Frequency 0: no band name, freq renders as 0.000000.
        assertThat(row.get("band")).isEmpty();
        assertThat(row.get("freq")).isEqualTo("0.000000");

        // Max long dial frequency must not overflow the freq formatter.
        QSLRecord maxFreq = new QSLRecord(START_MS + 60_000, END_MS + 60_000, "KS3CKC", "EM28",
                "BB2BB", "FN42", 595, 595, "SSTV", Long.MAX_VALUE, 0);
        assertThat(opr.doInsertQSLData(maxFreq, null)).isTrue();
        assertThat(queryQslRow("BB2BB").get("freq")).isEqualTo("9223372036854.775807");
    }

    // -----------------------------------------------------------------------
    // Dedup / update-existing paths
    // -----------------------------------------------------------------------

    @Test
    public void insert_sameRecordTwice_doesNotDuplicate() {
        final boolean[] newQsl = new boolean[2];
        assertThat(opr.doInsertQSLData(newRecord("K1AF"),
                (isInvalid, isNewQSL) -> newQsl[0] = isNewQSL)).isTrue();
        assertThat(opr.doInsertQSLData(newRecord("K1AF"),
                (isInvalid, isNewQSL) -> newQsl[1] = isNewQSL)).isTrue();

        assertThat(newQsl[0]).isTrue();   // first insert: brand new QSL
        assertThat(newQsl[1]).isFalse();  // second: existing record updated
        assertThat(countRows("QSLTable")).isEqualTo(1);
        assertThat(countRows("QslCallsigns")).isEqualTo(1);
    }

    @Test
    public void insert_existingRecord_backfillsFlagsGridAndReports() {
        // First pass: no grid, no confirmations, sentinel "no report" values.
        QSLRecord first = new QSLRecord(START_MS, END_MS, "KS3CKC", "EM28ax",
                "K1AF", "", -100, -100, "SSTV", 14_230_000L, 1500);
        assertThat(opr.doInsertQSLData(first, null)).isTrue();

        // Second pass, same identity (call+date+time_on+mode): brings the
        // grid, confirmations and real reports that must backfill the row.
        QSLRecord second = newRecord("K1AF");
        second.isQSL = true;
        second.isLotW_import = true;
        second.isLotW_QSL = true;
        assertThat(opr.doInsertQSLData(second, null)).isTrue();

        assertThat(countRows("QSLTable")).isEqualTo(1);
        Map<String, String> row = queryQslRow("K1AF");
        assertThat(row.get("gridsquare")).isEqualTo("FN42bb");
        assertThat(row.get("isQSL")).isEqualTo("1");
        assertThat(row.get("isLotW_import")).isEqualTo("1");
        assertThat(row.get("isLotW_QSL")).isEqualTo("1");
        assertThat(row.get("rst_sent")).isEqualTo("595");
        assertThat(row.get("rst_rcvd")).isEqualTo("585");

        // The companion QslCallsigns row picks up the grid and flags too.
        try (Cursor cursor = db.rawQuery(
                "select grid,isQSL,isLotW_import,isLotW_QSL from QslCallsigns where callsign='K1AF'",
                null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            assertThat(cursor.getString(0)).isEqualTo("FN42bb");
            assertThat(cursor.getInt(1)).isEqualTo(1);
            assertThat(cursor.getInt(2)).isEqualTo(1);
            assertThat(cursor.getInt(3)).isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // Update
    // -----------------------------------------------------------------------

    @Test
    public void updateQSLRecord_updatesSuppliedColumns() throws InterruptedException {
        assertThat(opr.doInsertQSLData(newRecord("K1AF"), null)).isTrue();
        int id = qslIdOf("K1AF");

        ContentValues values = new ContentValues();
        values.put("call", "W1AW");
        values.put("gridsquare", "FN31pr");
        values.put("mode", "SSTV");
        opr.updateQSLRecord(id, values);

        awaitTrue("updateQSLRecord to land", () -> {
            try (Cursor cursor = db.rawQuery(
                    "select count(*) from QSLTable where \"call\"='W1AW'", null)) {
                cursor.moveToFirst();
                return cursor.getInt(0) == 1;
            }
        });
        Map<String, String> row = queryQslRow("W1AW");
        assertThat(row.get("gridsquare")).isEqualTo("FN31pr");
        // Untouched columns keep their values.
        assertThat(row.get("freq")).isEqualTo("14.230000");
        assertThat(row.get("station_callsign")).isEqualTo("KS3CKC");
    }

    @Test
    public void updateQSLRecord_nullOrEmptyValues_isNoOp() throws InterruptedException {
        assertThat(opr.doInsertQSLData(newRecord("K1AF"), null)).isTrue();
        int id = qslIdOf("K1AF");

        opr.updateQSLRecord(id, null);
        opr.updateQSLRecord(id, new ContentValues());

        Thread.sleep(150); // give a hypothetical stray write time to land
        assertThat(queryQslRow("K1AF").get("call")).isEqualTo("K1AF");
        assertThat(countRows("QSLTable")).isEqualTo(1);
    }

    @Test
    public void setQSLTableIsQSL_togglesManualConfirmation() throws InterruptedException {
        assertThat(opr.doInsertQSLData(newRecord("K1AF"), null)).isTrue();
        int id = qslIdOf("K1AF");

        opr.setQSLTableIsQSL(true, id);
        awaitTrue("isQSL to become 1",
                () -> "1".equals(queryQslRow("K1AF").get("isQSL")));

        opr.setQSLTableIsQSL(false, id);
        awaitTrue("isQSL to become 0",
                () -> "0".equals(queryQslRow("K1AF").get("isQSL")));
    }

    @Test
    public void setQSLCallsignIsQSL_togglesFlagOnCallsignRow() throws InterruptedException {
        assertThat(opr.doInsertQSLData(newRecord("K1AF"), null)).isTrue();
        int id;
        try (Cursor cursor = db.rawQuery(
                "select ID from QslCallsigns where callsign='K1AF'", null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            id = cursor.getInt(0);
        }

        opr.setQSLCallsignIsQSL(true, id);
        awaitTrue("QslCallsigns.isQSL to become 1", () -> {
            try (Cursor cursor = db.rawQuery(
                    "select isQSL from QslCallsigns where ID=" + id, null)) {
                return cursor.moveToFirst() && cursor.getInt(0) == 1;
            }
        });
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    @Test
    public void deleteQSLByID_removesOnlyThatRow() throws InterruptedException {
        assertThat(opr.doInsertQSLData(newRecord("K1AF"), null)).isTrue();
        QSLRecord other = new QSLRecord(START_MS + 3_600_000, END_MS + 3_600_000,
                "KS3CKC", "EM28ax", "W1AW", "FN31pr", 595, 595, "SSTV", 7_171_000L, 1500);
        assertThat(opr.doInsertQSLData(other, null)).isTrue();
        assertThat(countRows("QSLTable")).isEqualTo(2);

        opr.deleteQSLByID(qslIdOf("K1AF"));

        awaitTrue("deleteQSLByID to land", () -> countRows("QSLTable") == 1);
        assertThat(queryQslRow("W1AW").get("call")).isEqualTo("W1AW");
    }

    @Test
    public void deleteQSLCallsign_removesCallsignRow() throws InterruptedException {
        assertThat(opr.doInsertQSLData(newRecord("K1AF"), null)).isTrue();
        int id;
        try (Cursor cursor = db.rawQuery(
                "select ID from QslCallsigns where callsign='K1AF'", null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            id = cursor.getInt(0);
        }

        opr.deleteQSLCallsign(id);

        awaitTrue("deleteQSLCallsign to land", () -> countRows("QslCallsigns") == 0);
        // The log row itself is untouched; only the callsign summary goes.
        assertThat(countRows("QSLTable")).isEqualTo(1);
    }
}
