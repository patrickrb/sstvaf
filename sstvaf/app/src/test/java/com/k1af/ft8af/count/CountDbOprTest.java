package com.k1af.ft8af.count;

import static com.google.common.truth.Truth.assertThat;

import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The Logbook stats aggregations in {@link CountDbOpr}: total/confirmation
 * counts, band distribution, and the DXCC / CQ-zone / ITU-zone completion
 * queries that join the log's grid squares against the lookup tables.
 *
 * A bare in-memory database with a minimal hand-built schema is used instead
 * of DatabaseOpr so the lookup tables (ituList/cqzoneList/dxcc*) hold exactly
 * the seeded rows — DatabaseOpr populates them from bundled assets on
 * background threads, which would make totals nondeterministic.
 *
 * Each CountDbOpr entry point runs an AsyncTask and reports through
 * AfterCount from the background thread; the zone/DXCC tasks fire the
 * callback twice (bar chart detail, then pie chart completion), so tests
 * latch on the expected callback count.
 */
@RunWith(RobolectricTestRunner.class)
public class CountDbOprTest {

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        // The QSLTable columns the count queries touch.
        db.execSQL("CREATE TABLE QSLTable (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " isQSL INTEGER DEFAULT 0, isLotW_QSL INTEGER DEFAULT 0,"
                + " \"call\" TEXT, gridsquare TEXT, band TEXT, freq TEXT,"
                + " qso_date TEXT, time_on TEXT)");
        db.execSQL("CREATE TABLE ituList (itu INTEGER, grid TEXT)");
        db.execSQL("CREATE TABLE cqzoneList (cqzone INTEGER, grid TEXT)");
        db.execSQL("CREATE TABLE dxccList (dxcc INTEGER, aname TEXT)");
        db.execSQL("CREATE TABLE dxcc_grid (dxcc INTEGER, grid TEXT)");
    }

    @After
    public void tearDown() {
        db.close();
    }

    private void seedQso(String call, String grid, String band, int isQSL, int isLotwQSL) {
        db.execSQL("INSERT INTO QSLTable (\"call\", gridsquare, band, freq, qso_date, time_on,"
                        + " isQSL, isLotW_QSL) VALUES (?,?,?,?,?,?,?,?)",
                new Object[]{call, grid, band, "14.230000", "20260112", "103000",
                        isQSL, isLotwQSL});
    }

    private interface Query {
        void run(SQLiteDatabase db, CountDbOpr.AfterCount afterCount);
    }

    /** Runs a CountDbOpr entry point and collects {@code expected} callbacks. */
    private List<CountDbOpr.CountInfo> collect(Query query, int expected)
            throws InterruptedException {
        List<CountDbOpr.CountInfo> infos =
                Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(expected);
        query.run(db, info -> {
            infos.add(info);
            latch.countDown();
        });
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        return infos;
    }

    // -----------------------------------------------------------------------
    // getQSLTotal — the "Total QSOs" tile
    // -----------------------------------------------------------------------

    @Test
    public void qslTotal_bucketsConfirmedVsUnconfirmed() throws InterruptedException {
        seedQso("K1AF", "FN42", "20m", 1, 0);   // manually confirmed
        seedQso("W1AW", "FN31", "20m", 0, 1);   // LotW confirmed
        seedQso("W1AW", "FN31", "40m", 1, 1);   // both (counted once)
        seedQso("VE3XYZ", "FN03", "20m", 0, 0); // unconfirmed
        seedQso("G4ABC", "IO91", "40m", 0, 0);  // unconfirmed

        List<CountDbOpr.CountInfo> infos =
                collect(CountDbOpr::getQSLTotal, 1);

        CountDbOpr.CountInfo info = infos.get(0);
        assertThat(info.chartType).isEqualTo(CountDbOpr.ChartType.Pie);
        assertThat(info.values).hasSize(2);
        // values[0] = confirmed (isQSL or isLotW_QSL), values[1] = the rest.
        assertThat(info.values.get(0).value).isEqualTo(3);
        assertThat(info.values.get(1).value).isEqualTo(2);
        // LogbookScreen sums the buckets for its "N QSOs" subtitle.
        assertThat(info.values.get(0).value + info.values.get(1).value).isEqualTo(5);
    }

    @Test
    public void qslTotal_emptyLogbookReportsZeroes() throws InterruptedException {
        List<CountDbOpr.CountInfo> infos =
                collect(CountDbOpr::getQSLTotal, 1);

        CountDbOpr.CountInfo info = infos.get(0);
        assertThat(info.values.get(0).value).isEqualTo(0);
        assertThat(info.values.get(1).value).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // getBandCount — the per-band distribution
    // -----------------------------------------------------------------------

    @Test
    public void bandCount_groupsCaseInsensitivelyAndOrdersByCountDesc()
            throws InterruptedException {
        seedQso("K1AF", "FN42", "20m", 0, 0);
        seedQso("W1AW", "FN31", "20M", 0, 0); // case variant of the same band
        seedQso("VE3XYZ", "FN03", "20m", 0, 0);
        seedQso("G4ABC", "IO91", "40m", 0, 0);

        List<CountDbOpr.CountInfo> infos =
                collect(CountDbOpr::getBandCount, 1);

        CountDbOpr.CountInfo info = infos.get(0);
        assertThat(info.chartType).isEqualTo(CountDbOpr.ChartType.Pie);
        assertThat(info.values).hasSize(2);
        assertThat(info.values.get(0).name).isEqualTo("20M"); // upper-cased bucket
        assertThat(info.values.get(0).value).isEqualTo(3);
        assertThat(info.values.get(1).name).isEqualTo("40M");
        assertThat(info.values.get(1).value).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // getDxcc — worked DXCC entities by grid prefix
    // -----------------------------------------------------------------------

    @Test
    public void dxccCount_matchesGridPrefixesAndReportsCompletion()
            throws InterruptedException {
        db.execSQL("INSERT INTO dxccList (dxcc, aname) VALUES (291, 'United States')");
        db.execSQL("INSERT INTO dxccList (dxcc, aname) VALUES (223, 'England')");
        db.execSQL("INSERT INTO dxccList (dxcc, aname) VALUES (1, 'Canada')");
        db.execSQL("INSERT INTO dxcc_grid (dxcc, grid) VALUES (291, 'FN42')");
        db.execSQL("INSERT INTO dxcc_grid (dxcc, grid) VALUES (291, 'EM28')");
        db.execSQL("INSERT INTO dxcc_grid (dxcc, grid) VALUES (223, 'IO91')");
        db.execSQL("INSERT INTO dxcc_grid (dxcc, grid) VALUES (1, 'FN03')");

        // 6-char and lower-case grids must still match on UPPER(SUBSTR(grid,1,4)).
        seedQso("K1AF", "FN42bb", "20m", 0, 0);
        seedQso("N0AB", "em28ax", "20m", 0, 0);
        seedQso("K5CD", "EM28", "40m", 0, 0);
        seedQso("G4ABC", "IO91wm", "20m", 0, 0);
        // No dxcc_grid entry -> contributes to no entity.
        seedQso("ZZ9ZZ", "AA00", "20m", 0, 0);

        List<CountDbOpr.CountInfo> infos = collect(CountDbOpr::getDxcc, 2);

        // First callback: bar chart of per-entity QSO counts, most-worked first.
        CountDbOpr.CountInfo bar = infos.get(0);
        assertThat(bar.chartType).isEqualTo(CountDbOpr.ChartType.Bar);
        assertThat(bar.values).hasSize(2);
        assertThat(bar.values.get(0).name).isEqualTo("United States");
        assertThat(bar.values.get(0).value).isEqualTo(3);
        assertThat(bar.values.get(1).name).isEqualTo("England");
        assertThat(bar.values.get(1).value).isEqualTo(1);

        // Second callback: completed vs incomplete out of all known entities.
        CountDbOpr.CountInfo pie = infos.get(1);
        assertThat(pie.chartType).isEqualTo(CountDbOpr.ChartType.Pie);
        assertThat(pie.values.get(0).value).isEqualTo(2); // worked: US + England
        assertThat(pie.values.get(1).value).isEqualTo(1); // remaining: Canada
    }

    // -----------------------------------------------------------------------
    // getCQZoneCount / getItuCount — zone completion
    // -----------------------------------------------------------------------

    @Test
    public void cqZoneCount_countsWorkedZonesByGridPrefix() throws InterruptedException {
        db.execSQL("INSERT INTO cqzoneList (cqzone, grid) VALUES (5, 'FN42')");
        db.execSQL("INSERT INTO cqzoneList (cqzone, grid) VALUES (5, 'FN31')");
        db.execSQL("INSERT INTO cqzoneList (cqzone, grid) VALUES (14, 'IO91')");
        db.execSQL("INSERT INTO cqzoneList (cqzone, grid) VALUES (33, 'IL38')");

        seedQso("K1AF", "FN42bb", "20m", 0, 0);
        seedQso("W1AW", "FN31pr", "20m", 0, 0);
        seedQso("G4ABC", "IO91wm", "20m", 0, 0);

        List<CountDbOpr.CountInfo> infos = collect(CountDbOpr::getCQZoneCount, 2);

        CountDbOpr.CountInfo bar = infos.get(0);
        assertThat(bar.chartType).isEqualTo(CountDbOpr.ChartType.Bar);
        // Zone 5 worked twice, zone 14 once; zone 33 never -> absent.
        assertThat(bar.values).hasSize(2);
        assertThat(bar.values.get(0).value).isEqualTo(2);
        assertThat(bar.values.get(1).value).isEqualTo(1);

        CountDbOpr.CountInfo pie = infos.get(1);
        assertThat(pie.chartType).isEqualTo(CountDbOpr.ChartType.Pie);
        assertThat(pie.values.get(0).value).isEqualTo(2); // zones worked
        assertThat(pie.values.get(1).value).isEqualTo(1); // of 3 known zones
    }

    @Test
    public void ituCount_countsWorkedZonesByGridPrefix() throws InterruptedException {
        db.execSQL("INSERT INTO ituList (itu, grid) VALUES (8, 'FN42')");
        db.execSQL("INSERT INTO ituList (itu, grid) VALUES (8, 'EM28')");
        db.execSQL("INSERT INTO ituList (itu, grid) VALUES (27, 'IO91')");

        seedQso("K1AF", "FN42bb", "20m", 0, 0);
        seedQso("N0AB", "EM28ax", "20m", 0, 0);
        seedQso("K5CD", "EM28ix", "40m", 0, 0);

        List<CountDbOpr.CountInfo> infos = collect(CountDbOpr::getItuCount, 2);

        CountDbOpr.CountInfo bar = infos.get(0);
        assertThat(bar.chartType).isEqualTo(CountDbOpr.ChartType.Bar);
        // Only ITU zone 8 is worked (3 QSOs across two of its grids).
        assertThat(bar.values).hasSize(1);
        assertThat(bar.values.get(0).value).isEqualTo(3);

        CountDbOpr.CountInfo pie = infos.get(1);
        assertThat(pie.chartType).isEqualTo(CountDbOpr.ChartType.Pie);
        assertThat(pie.values.get(0).value).isEqualTo(1); // 1 zone worked
        assertThat(pie.values.get(1).value).isEqualTo(1); // of 2 known zones
    }

    @Test
    public void zoneCounts_emptyLogbookYieldNoWorkedZones() throws InterruptedException {
        db.execSQL("INSERT INTO cqzoneList (cqzone, grid) VALUES (5, 'FN42')");

        List<CountDbOpr.CountInfo> infos = collect(CountDbOpr::getCQZoneCount, 2);

        assertThat(infos.get(0).values).isEmpty();          // no bars
        assertThat(infos.get(1).values.get(0).value).isEqualTo(0); // 0 of 1 worked
        assertThat(infos.get(1).values.get(1).value).isEqualTo(1);
    }
}
