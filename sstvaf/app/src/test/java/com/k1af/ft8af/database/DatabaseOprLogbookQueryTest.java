package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.log.QSLCallsignRecord;
import com.k1af.ft8af.log.QSLRecordStr;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The read queries the Logbook screen actually issues:
 * {@code getQSLCallsignsByCallsign} (LogbookScreen's row source — grouped,
 * ordered newest-first with time_on width normalization, filtered, paged)
 * and {@code getQSLRecordByCallsign} (the per-callsign detail records).
 *
 * Rows are seeded straight into QSLTable so odd shapes (variable-width
 * time_on, confirmation flags) can be controlled exactly. Both queries run
 * on AsyncTask background executors and deliver via callback, so each test
 * blocks on a latch.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprLogbookQueryTest {

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

    private void seedQso(String call, String date, String timeOn,
                         String grid, String band, String freq, String mode,
                         int isQSL, int isLotwQSL) {
        db.execSQL("INSERT INTO QSLTable (\"call\", qso_date, time_on, qso_date_off, time_off,"
                        + " gridsquare, band, freq, mode, isQSL, isLotW_QSL, comment,"
                        + " station_callsign, my_gridsquare, rst_sent, rst_rcvd)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{call, date, timeOn, date, timeOn, grid, band, freq, mode,
                        isQSL, isLotwQSL, "QSO by SSTVAF", "KS3CKC", "EM28", "595", "595"});
    }

    /** LogbookScreen's exact call shape: opr.getQSLCallsignsByCallsign(true, 0, "", 0). */
    private ArrayList<QSLCallsignRecord> queryCallsigns(
            boolean showAll, int offset, String callsign, int filter) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ArrayList<QSLCallsignRecord>> out = new AtomicReference<>();
        opr.getQSLCallsignsByCallsign(showAll, offset, callsign, filter, records -> {
            out.set(records);
            latch.countDown();
        });
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        return out.get();
    }

    private ArrayList<QSLRecordStr> queryRecords(
            boolean showAll, int offset, String callsign, int filter) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ArrayList<QSLRecordStr>> out = new AtomicReference<>();
        opr.getQSLRecordByCallsign(showAll, offset, callsign, filter, records -> {
            out.set(records);
            latch.countDown();
        });
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        return out.get();
    }

    // -----------------------------------------------------------------------
    // getQSLCallsignsByCallsign — the Logbook row source
    // -----------------------------------------------------------------------

    @Test
    public void callsignQuery_ordersNewestFirstByDateThenTime() throws InterruptedException {
        seedQso("AA1AA", "20260110", "101500", "FN42", "20m", "14.230000", "SSTV", 0, 0);
        seedQso("BB2BB", "20260112", "081500", "FN31", "20m", "14.230000", "SSTV", 0, 0);
        seedQso("CC3CC", "20260112", "103000", "EM28", "20m", "14.230000", "SSTV", 0, 0);

        ArrayList<QSLCallsignRecord> records = queryCallsigns(true, 0, "", 0);

        assertThat(records).hasSize(3);
        assertThat(records.get(0).getCallsign()).isEqualTo("CC3CC"); // newest day, later time
        assertThat(records.get(1).getCallsign()).isEqualTo("BB2BB");
        assertThat(records.get(2).getCallsign()).isEqualTo("AA1AA"); // oldest day
    }

    @Test
    public void callsignQuery_normalizesVariableWidthTimeOn() throws InterruptedException {
        // "815" is 08:15 with the leading zero dropped (ADIF import / manual
        // edit can store variable-width time_on). Lexically "815" > "103000",
        // so without normalization the 08:15 QSO would wrongly sort first.
        seedQso("AA1AA", "20260112", "815", "FN42", "20m", "14.230000", "SSTV", 0, 0);
        seedQso("BB2BB", "20260112", "103000", "FN31", "20m", "14.230000", "SSTV", 0, 0);

        ArrayList<QSLCallsignRecord> records = queryCallsigns(true, 0, "", 0);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).getCallsign()).isEqualTo("BB2BB");
        assertThat(records.get(1).getCallsign()).isEqualTo("AA1AA");
        // And the normalized fixed-width value is what comes back.
        assertThat(records.get(1).getTimeOn()).isEqualTo("081500");
        assertThat(records.get(0).getTimeOn()).isEqualTo("103000");
    }

    @Test
    public void callsignQuery_collapsesIdenticalRowsToMaxId() throws InterruptedException {
        // Two identical QSO rows (dupe import) must yield one grouped entry
        // carrying the highest row id and the composite band string.
        seedQso("K1AF", "20260112", "103000", "FN42", "20m", "14.230000", "SSTV", 0, 0);
        seedQso("K1AF", "20260112", "103000", "FN42", "20m", "14.230000", "SSTV", 0, 0);

        ArrayList<QSLCallsignRecord> records = queryCallsigns(true, 0, "", 0);

        assertThat(records).hasSize(1);
        QSLCallsignRecord record = records.get(0);
        assertThat(record.getCallsign()).isEqualTo("K1AF");
        assertThat(record.id).isEqualTo(2); // max(id) of the two seeded rows
        assertThat(record.getGrid()).isEqualTo("FN42");
        assertThat(record.getMode()).isEqualTo("SSTV");
        assertThat(record.getLastTime()).isEqualTo("20260112");
        assertThat(record.getBand()).isEqualTo("20m(14.230000 MHz)");
    }

    @Test
    public void callsignQuery_filterConfirmedAndUnconfirmed() throws InterruptedException {
        seedQso("AA1AA", "20260110", "101500", "FN42", "20m", "14.230000", "SSTV", 1, 0);
        seedQso("BB2BB", "20260111", "101500", "FN31", "20m", "14.230000", "SSTV", 0, 1);
        seedQso("CC3CC", "20260112", "101500", "EM28", "20m", "14.230000", "SSTV", 0, 0);

        // filter=1: confirmed (manual OR LotW)
        ArrayList<QSLCallsignRecord> confirmed = queryCallsigns(true, 0, "", 1);
        assertThat(confirmed).hasSize(2);
        assertThat(confirmed.get(0).getCallsign()).isEqualTo("BB2BB");
        assertThat(confirmed.get(0).isLotW_QSL).isTrue();
        assertThat(confirmed.get(1).getCallsign()).isEqualTo("AA1AA");
        assertThat(confirmed.get(1).isQSL).isTrue();

        // filter=2: unconfirmed only
        ArrayList<QSLCallsignRecord> unconfirmed = queryCallsigns(true, 0, "", 2);
        assertThat(unconfirmed).hasSize(1);
        assertThat(unconfirmed.get(0).getCallsign()).isEqualTo("CC3CC");
    }

    @Test
    public void callsignQuery_matchesCallsignSubstring() throws InterruptedException {
        seedQso("K1AF", "20260112", "101500", "FN42", "20m", "14.230000", "SSTV", 0, 0);
        seedQso("W9XYZ", "20260112", "103000", "EN52", "20m", "14.230000", "SSTV", 0, 0);

        ArrayList<QSLCallsignRecord> records = queryCallsigns(true, 0, "1AF", 0);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getCallsign()).isEqualTo("K1AF");
    }

    @Test
    public void callsignQuery_searchWithApostrophe_isSafeAndMatches() throws InterruptedException {
        seedQso("O'BRIEN", "20260112", "101500", "IO63", "20m", "14.230000", "SSTV", 0, 0);
        seedQso("K1AF", "20260112", "103000", "FN42", "20m", "14.230000", "SSTV", 0, 0);

        // The LIKE pattern is bound, so an apostrophe must neither crash nor
        // change the query shape.
        ArrayList<QSLCallsignRecord> records = queryCallsigns(true, 0, "O'BRI", 0);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getCallsign()).isEqualTo("O'BRIEN");
    }

    @Test
    public void callsignQuery_pagesInHundredsWhenNotShowingAll() throws InterruptedException {
        // 105 distinct callsigns, one row each.
        for (int i = 0; i < 105; i++) {
            seedQso(String.format("K%03dAA", i), "20260112",
                    String.format("%06d", i), "FN42", "20m", "14.230000", "SSTV", 0, 0);
        }

        assertThat(queryCallsigns(true, 0, "", 0)).hasSize(105);
        assertThat(queryCallsigns(false, 0, "", 0)).hasSize(100);
        ArrayList<QSLCallsignRecord> lastPage = queryCallsigns(false, 100, "", 0);
        assertThat(lastPage).hasSize(5);
    }

    // -----------------------------------------------------------------------
    // getQSLRecordByCallsign — per-callsign log records
    // -----------------------------------------------------------------------

    @Test
    public void recordQuery_populatesEveryFieldOfTheRecord() throws InterruptedException {
        seedQso("K1AF", "20260112", "103000", "FN42bb", "20m", "14.230000", "SSTV", 1, 0);

        ArrayList<QSLRecordStr> records = queryRecords(true, 0, "K1AF", 0);

        assertThat(records).hasSize(1);
        QSLRecordStr record = records.get(0);
        assertThat(record.getCall()).isEqualTo("K1AF");
        assertThat(record.isQSL).isTrue();
        assertThat(record.isLotW_QSL).isFalse();
        assertThat(record.getGridsquare()).isEqualTo("FN42bb");
        assertThat(record.getMode()).isEqualTo("SSTV");
        assertThat(record.getRst_sent()).isEqualTo("595");
        assertThat(record.getRst_rcvd()).isEqualTo("595");
        // time_on/time_off come back as "qso_date-time" composites.
        assertThat(record.getTime_on()).isEqualTo("20260112-103000");
        assertThat(record.getTime_off()).isEqualTo("20260112-103000");
        assertThat(record.getBand()).isEqualTo("20m");
        assertThat(record.getFreq()).isEqualTo("14.230000");
        assertThat(record.getStation_callsign()).isEqualTo("KS3CKC");
        assertThat(record.getMy_gridsquare()).isEqualTo("EM28");
        assertThat(record.getComment()).isEqualTo("QSO by SSTVAF");
    }

    @Test
    public void recordQuery_ordersByDateThenTimeOffDescending() throws InterruptedException {
        seedQso("K1AF", "20260110", "235900", "FN42", "20m", "14.230000", "SSTV", 0, 0);
        seedQso("K1AF", "20260112", "081500", "FN42", "20m", "14.230000", "SSTV", 0, 0);
        seedQso("K1AF", "20260112", "103000", "FN42", "20m", "14.230000", "SSTV", 0, 0);

        ArrayList<QSLRecordStr> records = queryRecords(true, 0, "K1AF", 0);

        assertThat(records).hasSize(3);
        assertThat(records.get(0).getTime_on()).isEqualTo("20260112-103000");
        assertThat(records.get(1).getTime_on()).isEqualTo("20260112-081500");
        assertThat(records.get(2).getTime_on()).isEqualTo("20260110-235900");
    }

    @Test
    public void recordQuery_appliesConfirmationFilter() throws InterruptedException {
        seedQso("K1AF", "20260110", "101500", "FN42", "20m", "14.230000", "SSTV", 0, 0);
        seedQso("K1AF", "20260111", "101500", "FN42", "20m", "14.230000", "SSTV", 0, 1);

        ArrayList<QSLRecordStr> confirmed = queryRecords(true, 0, "K1AF", 1);
        assertThat(confirmed).hasSize(1);
        assertThat(confirmed.get(0).isLotW_QSL).isTrue();

        ArrayList<QSLRecordStr> unconfirmed = queryRecords(true, 0, "K1AF", 2);
        assertThat(unconfirmed).hasSize(1);
        assertThat(unconfirmed.get(0).isLotW_QSL).isFalse();
    }

    @Test
    public void recordQuery_emptyDatabaseReturnsEmptyList() throws InterruptedException {
        assertThat(queryRecords(true, 0, "", 0)).isEmpty();
        assertThat(queryCallsigns(true, 0, "", 0)).isEmpty();
    }
}
