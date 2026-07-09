package com.k1af.ft8af.log

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for [ThirdPartyService.countUnsyncedQSOs] — the gate the auto-sync uses to
 * decide whether anything is actually pending before spawning an upload pass. It must
 * agree with the WHERE clause [ThirdPartyService.syncAllQSOs] uses, keyed off the
 * Cloudlog enable flag. Drives a real (Robolectric) in-memory SQLite database.
 */
@RunWith(RobolectricTestRunner::class)
class ThirdPartyServiceUnsyncedCountTest {

    private lateinit var db: SQLiteDatabase
    private var savedCloudlog = false

    @Before
    fun setUp() {
        savedCloudlog = GeneralVariables.enableCloudlog
        db = SQLiteDatabase.create(null)
        db.execSQL(
            """
            CREATE TABLE QSLTable (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                synced_cloudlog INTEGER DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        GeneralVariables.enableCloudlog = savedCloudlog
    }

    private fun insert(cloudlog: Int) {
        db.execSQL(
            "INSERT INTO QSLTable (synced_cloudlog) VALUES (?)",
            arrayOf<Any>(cloudlog),
        )
    }

    @Test
    fun zero_whenCloudlogDisabled() {
        GeneralVariables.enableCloudlog = false
        insert(cloudlog = 0) // pending, but the service is disabled
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(0)
    }

    @Test
    fun zero_whenDbNull() {
        GeneralVariables.enableCloudlog = true
        assertThat(ThirdPartyService.countUnsyncedQSOs(null)).isEqualTo(0)
    }

    @Test
    fun countsOnlyPendingRows_whenCloudlogEnabled() {
        GeneralVariables.enableCloudlog = true
        insert(cloudlog = 0) // needs cloudlog -> counted
        insert(cloudlog = 1) // already synced -> not counted
        insert(cloudlog = 0) // needs cloudlog -> counted
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(2)
    }
}
