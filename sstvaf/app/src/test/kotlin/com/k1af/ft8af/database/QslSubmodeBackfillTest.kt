package com.k1af.ft8af.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.log.QSLRecord
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * doInsertQSLData's existing-row path must backfill `submode` when a matching
 * record arrives with a value (re-import / catch-up sync filling rows that
 * predate the v20 column), and an EMPTY incoming submode must never clobber a
 * stored one — the same overwrite-only-with-substance pattern the grid
 * updates use.
 */
@RunWith(RobolectricTestRunner::class)
class QslSubmodeBackfillTest {

    private lateinit var context: Context
    private lateinit var opr: DatabaseOpr
    private val dbName = "submode-backfill-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        opr = DatabaseOpr(context, dbName, null, 20) // current schema (v20 adds submode)
    }

    @After
    fun tearDown() {
        opr.close()
        context.deleteDatabase(dbName)
    }

    /** Same call/start-time/mode ⇒ doInsertQSLData takes the existing-row path. */
    private fun record(submode: String): QSLRecord =
        QSLRecord(
            1_780_000_000_000L, 1_780_000_037_000L,
            "KS3CKC", "FN20", "W1AW", "FN31",
            595, 595, "SSTV", 14_230_000L, 0,
        ).apply { setSubmode(submode) }

    private fun storedSubmode(): String? =
        opr.db.rawQuery(
            "select submode from QSLTable where \"call\"='W1AW'",
            null,
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.getString(0)
        }

    private fun rowCount(): Int =
        opr.db.rawQuery("select count(*) from QSLTable", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    @Test
    fun reimportWithSubmode_backfillsTheExistingRow() {
        opr.doInsertQSLData(record(submode = ""), null) // legacy-style row
        assertThat(storedSubmode()).isEmpty()

        opr.doInsertQSLData(record(submode = "Scottie 1"), null) // re-import

        assertThat(rowCount()).isEqualTo(1) // updated, not duplicated
        assertThat(storedSubmode()).isEqualTo("Scottie 1")
    }

    @Test
    fun emptyIncomingSubmode_neverClobbersAStoredOne() {
        opr.doInsertQSLData(record(submode = "Scottie 1"), null)
        opr.doInsertQSLData(record(submode = ""), null)

        assertThat(storedSubmode()).isEqualTo("Scottie 1")
    }
}
