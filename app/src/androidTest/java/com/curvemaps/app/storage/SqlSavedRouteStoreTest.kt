package com.curvemaps.app.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the [SavedRouteStore] contract against real device SQLite, including
 * the foreign-key cascade that an in-memory fake can only imitate.
 */
@RunWith(AndroidJUnit4::class)
class SqlSavedRouteStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: SqlSavedRouteStore

    @Before
    fun resetDatabase() {
        context.deleteDatabase(SAVED_ROUTES_DB_NAME)
        store = SqlSavedRouteStore(context)
    }

    @Test
    fun insertsAndReadsBackEveryColumn() {
        val id = store.insertRoute(savedRoute(fromName = "Brisbane"))
        val summary = store.summaries().single()

        assertEquals(id, summary.route.id)
        assertEquals("Brisbane", summary.route.fromName)
        assertEquals(-27.4679, summary.route.fromLat, 1e-9)
        assertEquals(153.0281, summary.route.fromLon, 1e-9)
        assertEquals("Byron Bay", summary.route.toName)
        assertEquals(-28.6438, summary.route.toLat, 1e-9)
        assertEquals(153.6096, summary.route.toLon, 1e-9)
        assertEquals(190_000.0, summary.route.distanceMeters, 1e-9)
        assertEquals(10_800_000L, summary.route.durationMillis)
        assertEquals(2f, summary.route.complexity)
        assertTrue(summary.route.avoidMotorways)
        assertTrue(summary.route.blockUnpaved)
        assertTrue(summary.route.includeElevation)
        assertTrue(summary.route.avoidStopsAndTowns)
        assertEquals(ENCODED_GEOMETRY, summary.route.geometry)
        assertEquals(CREATED_AT, summary.route.createdAtMillis)
        assertEquals(0, summary.commentCount)
    }

    /**
     * A device that saved rides on schema v1 (road share, no motorway flag)
     * upgrades in place: every row and comment survives and reads as
     * "motorways not avoided", and new rows round-trip the flag.
     */
    @Test
    fun upgradesVersionOneDatabaseInPlace() {
        context.deleteDatabase(SAVED_ROUTES_DB_NAME)
        val file = context.getDatabasePath(SAVED_ROUTES_DB_NAME)
        file.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE saved_routes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    from_name TEXT NOT NULL, from_lat REAL NOT NULL, from_lon REAL NOT NULL,
                    to_name TEXT NOT NULL, to_lat REAL NOT NULL, to_lon REAL NOT NULL,
                    distance_meters REAL NOT NULL, duration_millis INTEGER NOT NULL,
                    complexity REAL NOT NULL, max_road_share_percent REAL NOT NULL,
                    block_unpaved INTEGER NOT NULL, geometry TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE saved_route_comments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    route_id INTEGER NOT NULL REFERENCES saved_routes(id) ON DELETE CASCADE,
                    text TEXT NOT NULL, created_at_millis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO saved_routes VALUES (7, 'Old', -27.4, 153.0, 'Ride', -28.6, 153.6, " +
                    "1000.0, 60000, 9.0, 45.0, 1, '$ENCODED_GEOMETRY', $CREATED_AT)",
            )
            db.execSQL("INSERT INTO saved_route_comments VALUES (1, 7, 'kept', $CREATED_AT)")
            db.version = 1
        }

        store = SqlSavedRouteStore(context)
        val legacy = store.summaries().single()
        assertEquals(7L, legacy.route.id)
        assertEquals("Old", legacy.route.fromName)
        // The endless dial's level 9 clamps to Max twisty on the seven-stop slider.
        assertEquals(6f, legacy.route.complexity)
        assertTrue(legacy.route.blockUnpaved)
        assertFalse(legacy.route.avoidMotorways)
        assertEquals(1, legacy.commentCount)
        assertEquals("kept", store.comments(7L).single().text)

        val newId = store.insertRoute(savedRoute(fromName = "New"))
        assertTrue(store.summaries().single { it.route.id == newId }.route.avoidMotorways)
    }

    @Test
    fun upgradesVersionTwoRideStylesOntoTheSevenStopSlider() {
        context.deleteDatabase(SAVED_ROUTES_DB_NAME)
        val file = context.getDatabasePath(SAVED_ROUTES_DB_NAME)
        file.parentFile?.mkdirs()
        // Four-stop levels (Fastest 0 .. Max twisty 3) and their new stops.
        val levels = listOf(0.0 to 0f, 1.0 to 2f, 2.0 to 4f, 3.0 to 6f, 1.4 to 2f, 2.5 to 6f)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE saved_routes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    from_name TEXT NOT NULL, from_lat REAL NOT NULL, from_lon REAL NOT NULL,
                    to_name TEXT NOT NULL, to_lat REAL NOT NULL, to_lon REAL NOT NULL,
                    distance_meters REAL NOT NULL, duration_millis INTEGER NOT NULL,
                    complexity REAL NOT NULL, max_road_share_percent REAL NOT NULL,
                    block_unpaved INTEGER NOT NULL, geometry TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL,
                    avoid_motorways INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE saved_route_comments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    route_id INTEGER NOT NULL REFERENCES saved_routes(id) ON DELETE CASCADE,
                    text TEXT NOT NULL, created_at_millis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            levels.forEachIndexed { index, (old, _) ->
                db.execSQL(
                    "INSERT INTO saved_routes VALUES (${index + 1}, 'Old', -27.4, 153.0, 'Ride', -28.6, 153.6, " +
                        "1000.0, 60000, $old, 60.0, 0, '$ENCODED_GEOMETRY', $CREATED_AT, 1)",
                )
            }
            db.version = 2
        }

        store = SqlSavedRouteStore(context)
        val upgraded = store.summaries().associate { it.route.id to it.route.complexity }
        levels.forEachIndexed { index, (old, new) ->
            assertEquals("level $old", new, upgraded.getValue(index + 1L))
        }
        // New rows are stored as given: no second rescale.
        val newId = store.insertRoute(savedRoute(fromName = "New"))
        assertEquals(savedRoute(fromName = "New").complexity, store.summaries().single { it.route.id == newId }.route.complexity)
    }

    /** Rides saved on schema v3 reload with both ride factors off, as they were routed. */
    @Test
    fun upgradesVersionThreeRidesWithRideFactorsOff() {
        context.deleteDatabase(SAVED_ROUTES_DB_NAME)
        val file = context.getDatabasePath(SAVED_ROUTES_DB_NAME)
        file.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE saved_routes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    from_name TEXT NOT NULL, from_lat REAL NOT NULL, from_lon REAL NOT NULL,
                    to_name TEXT NOT NULL, to_lat REAL NOT NULL, to_lon REAL NOT NULL,
                    distance_meters REAL NOT NULL, duration_millis INTEGER NOT NULL,
                    complexity REAL NOT NULL, max_road_share_percent REAL NOT NULL,
                    block_unpaved INTEGER NOT NULL, geometry TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL,
                    avoid_motorways INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE saved_route_comments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    route_id INTEGER NOT NULL REFERENCES saved_routes(id) ON DELETE CASCADE,
                    text TEXT NOT NULL, created_at_millis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO saved_routes VALUES (3, 'Old', -27.4, 153.0, 'Ride', -28.6, 153.6, " +
                    "1000.0, 60000, 4.0, 60.0, 1, '$ENCODED_GEOMETRY', $CREATED_AT, 1)",
            )
            db.execSQL("INSERT INTO saved_route_comments VALUES (1, 3, 'kept', $CREATED_AT)")
            db.version = 3
        }

        store = SqlSavedRouteStore(context)
        val legacy = store.summaries().single()
        assertEquals(3L, legacy.route.id)
        // No rescale on this hop: v3 levels are already seven-stop.
        assertEquals(4f, legacy.route.complexity)
        assertTrue(legacy.route.blockUnpaved)
        assertTrue(legacy.route.avoidMotorways)
        assertFalse(legacy.route.includeElevation)
        assertFalse(legacy.route.avoidStopsAndTowns)
        assertEquals("kept", store.comments(3L).single().text)

        val newId = store.insertRoute(savedRoute(fromName = "New"))
        val fresh = store.summaries().single { it.route.id == newId }.route
        assertTrue(fresh.includeElevation && fresh.avoidStopsAndTowns)
    }

    @Test
    fun summariesSortNewestFirstAndCountComments() {
        val older = store.insertRoute(savedRoute(createdOffsetMinutes = 0))
        val newer = store.insertRoute(savedRoute(createdOffsetMinutes = 10))
        store.insertComment(SavedRouteComment(0L, older, "curvy!", CREATED_AT))

        val summaries = store.summaries()
        assertEquals(listOf(newer, older), summaries.map { it.route.id })
        assertEquals(0, summaries[0].commentCount)
        assertEquals(1, summaries[1].commentCount)
    }

    @Test
    fun commentsListOldestFirst() {
        val routeId = store.insertRoute(savedRoute())
        store.insertComment(SavedRouteComment(0L, routeId, "first", CREATED_AT))
        store.insertComment(SavedRouteComment(0L, routeId, "second", CREATED_AT + 5_000))

        assertEquals(listOf("first", "second"), store.comments(routeId).map { it.text })
    }

    @Test
    fun insertingCommentOnUnknownRouteFails() {
        assertThrows(IllegalArgumentException::class.java) {
            store.insertComment(SavedRouteComment(0L, routeId = 4242L, "orphan", CREATED_AT))
        }
    }

    @Test
    fun deleteUnknownRouteIsNoOp() {
        val id = store.insertRoute(savedRoute())
        store.deleteRoute(id + 1000L)
        assertEquals(listOf(id), store.summaries().map { it.route.id })
    }

    @Test
    fun commentTextIsStoredVerbatim() {
        val routeId = store.insertRoute(savedRoute())
        val raw = "  keep this text  "
        store.insertComment(SavedRouteComment(0L, routeId, raw, CREATED_AT))
        assertEquals(raw, store.comments(routeId).single().text)
    }

    @Test
    fun twoSavesProduceTwoIndependentRows() {
        val first = store.insertRoute(savedRoute(fromName = "First"))
        val second = store.insertRoute(savedRoute(fromName = "Second"))
        assertTrue(first != second)
        assertEquals(setOf("First", "Second"), store.summaries().map { it.route.fromName }.toSet())
    }

    @Test
    fun deletingRouteCascadesToComments() {
        val routeId = store.insertRoute(savedRoute())
        store.insertComment(SavedRouteComment(0L, routeId, "note", CREATED_AT))

        store.deleteRoute(routeId)

        assertTrue(store.summaries().isEmpty())
        assertTrue(store.comments(routeId).isEmpty())
    }

    @Test
    fun dataSurvivesReopeningDatabaseFile() {
        val routeId = store.insertRoute(savedRoute())

        // A fresh helper instance over the same file proves disk persistence,
        // not just connection state.
        val reopened = SqlSavedRouteStore(context)
        assertEquals(routeId, reopened.summaries().single().route.id)
    }

    private fun savedRoute(
        fromName: String = "Brisbane",
        createdOffsetMinutes: Long = 0,
    ) = SavedRoute(
        id = 0L,
        fromName = fromName,
        fromLat = -27.4679,
        fromLon = 153.0281,
        toName = "Byron Bay",
        toLat = -28.6438,
        toLon = 153.6096,
        distanceMeters = 190_000.0,
        durationMillis = 10_800_000L,
        complexity = 2f,
        avoidMotorways = true,
        blockUnpaved = true,
        geometry = ENCODED_GEOMETRY,
        createdAtMillis = CREATED_AT + createdOffsetMinutes * 60_000,
        includeElevation = true,
        avoidStopsAndTowns = true,
    )

    private companion object {
        const val CREATED_AT = 1_756_000_000_000L

        /** Two-point Brisbane → Byron-ish line; content is opaque to SQL. */
        const val ENCODED_GEOMETRY = "_p~iF~ps|U_ulLnnqC"
    }
}
