package com.curvemaps.app.rater

import com.curvemaps.app.geocoding.GeocoderIndex
import com.curvemaps.app.geocoding.TestDoc
import com.curvemaps.app.geocoding.buildIndex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

/** The rater's pure parts: curvy runs, the ratings store, and `.motomap` installs. */
class RaterPureTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A polyline in metres around (-27, 153), as the rater's lat/lon arrays. */
    private class Track {
        val xy = mutableListOf(0.0 to 0.0)
        private var heading = 0.0

        fun straight(meters: Double) = apply { repeat((meters / 10).toInt()) { step(10.0) } }

        /** Alternating 40 m radius bends. */
        fun bends(meters: Double) = apply {
            var turned = 0.0
            var sign = 1.0
            repeat((meters / 10).toInt()) {
                heading += sign * 10.0 / 40.0
                turned += 10.0 / 40.0
                if (turned > Math.PI / 2) { sign = -sign; turned = 0.0 }
                step(10.0)
            }
        }

        private fun step(d: Double) {
            val (x, y) = xy.last()
            xy += (x + d * cos(heading)) to (y + d * sin(heading))
        }

        fun runs(): CurvyRuns {
            val lats = xy.map { -27.0 + it.second / 111_195.0 }.toDoubleArray()
            val lons = xy.map { 153.0 + it.first / (111_195.0 * cos(Math.toRadians(-27.0))) }.toDoubleArray()
            return curvyRuns(lats, lons)
        }
    }

    @Test
    fun aStraightRoadHasNoCurvyRun() {
        val runs = Track().straight(3_000.0).runs()
        assertEquals(0.0, runs.longestRunMeters, 0.0)
        assertEquals(0, runs.bends)
    }

    @Test
    fun shortStraightsBetweenBendsStayOneRunAndLongOnesSplitIt() {
        val bridged = Track().bends(1_500.0).straight(200.0).bends(1_500.0).runs()
        assertTrue("bridged run ${bridged.longestRunMeters}", bridged.longestRunMeters > 3_000.0)
        assertEquals(1, bridged.runsOver2Km)

        val split = Track().bends(1_500.0).straight(1_000.0).bends(1_500.0).runs()
        assertTrue("split run ${split.longestRunMeters}", split.longestRunMeters < 1_700.0)
        assertEquals(0, split.runsOver2Km)
        assertTrue(split.tightBends > 0)
    }

    /** A polyline walked from (lat, lon) by (bearingDegrees, metres) legs. */
    private fun walk(vararg legs: Pair<Double, Double>): Pair<DoubleArray, DoubleArray> {
        val lats = mutableListOf(-27.0)
        val lons = mutableListOf(153.0)
        legs.forEach { (bearing, meters) ->
            repeat((meters / 20).toInt()) {
                val b = Math.toRadians(bearing)
                lats += lats.last() + 20 * cos(b) / 111_195.0
                lons += lons.last() + 20 * sin(b) / (111_195.0 * cos(Math.toRadians(lats.last())))
            }
        }
        return lats.toDoubleArray() to lons.toDoubleArray()
    }

    @Test
    fun aStraightRideHasNoGeometryOddities() {
        val (lats, lons) = walk(90.0 to 10_000.0)
        assertTrue(geometryOddities(lats, lons).isEmpty())
    }

    @Test
    fun anOutAndBackSpurIsAReturnAtItsKm() {
        // 3 km east, 1 km north and straight back, then 3 km on east.
        val (lats, lons) = walk(90.0 to 3_000.0, 0.0 to 1_000.0, 180.0 to 1_000.0, 90.0 to 3_000.0)
        val found = geometryOddities(lats, lons)
        assertEquals(found.toString(), 1, found.size)
        assertEquals("returns to an earlier point", found[0].kind)
        assertTrue(found[0].toString(), found[0].fromKm in 2.5..3.1 && found[0].toKm in 4.9..5.5)
    }

    @Test
    fun aUDetourOnAParallelRoadDoublesBack() {
        // Out 1 km north, 400 m across, back 1 km south: never within 60 m of itself.
        val (lats, lons) = walk(90.0 to 3_000.0, 0.0 to 1_000.0, 90.0 to 400.0, 180.0 to 1_000.0, 90.0 to 3_000.0)
        val found = geometryOddities(lats, lons)
        assertEquals(found.toString(), listOf("doubles back"), found.map { it.kind })
        assertTrue(found[0].toString(), found[0].fromKm < 3.5 && found[0].toKm > 4.9)
    }

    /** Segment rows; each starts [eastMeters] east of a fixed point (default: as far east as its km, i.e. a straight road). */
    private fun rows(vararg rows: Triple<String, Double, Double>, eastMeters: (Int) -> Double? = { null }, turn: (String) -> String = { "left" }) =
        JSONArray(rows.mapIndexed { i, (name, from, to) ->
            val east = eastMeters(i) ?: (from * 1000)
            JSONObject().put("name", name).put("fromKm", from).put("toKm", to).put("turn", turn(name))
                .put("lat", -27.0).put("lon", 153.0).put("startLat", -27.0).put("startLon", 153.0 + east / (111_195.0 * cos(Math.toRadians(27.0))))
        })

    @Test
    fun aBlockJogBackOntoTheSameRoadIsOneReentry() {
        // The Brisbane CBD shape: Eagle Tce → Skew St → Eagle Tce → Skew St → Upper Roma St.
        val found = reentryOddities(rows(
            Triple("Ann Street", 0.0, 1.12), Triple("Eagle Terrace", 1.16, 1.21), Triple("(unnamed)", 1.21, 1.28),
            Triple("Skew Street", 1.28, 1.36), Triple("Eagle Terrace", 1.36, 1.43), Triple("Skew Street", 1.43, 1.59),
            Triple("Upper Roma Street", 1.59, 1.78),
            eastMeters = { listOf(0.0, 1_160.0, 1_210.0, 1_250.0, 1_220.0, 1_260.0, 1_380.0)[it] },
        ))
        assertEquals(found.toString(), 1, found.size)
        assertEquals("leaves and rejoins a road", found[0].kind)
        assertEquals(1.21, found[0].fromKm, 1e-9)
        assertEquals(1.36, found[0].toKm, 1e-9)
    }

    @Test
    fun aViaSplitARenamedThroughRoadOrALongWayRoundIsNotAReentry() {
        val via = reentryOddities(rows(Triple("Guanaba Road", 17.89, 18.12), Triple("(unnamed)", 18.12, 18.12), Triple("Guanaba Road", 18.12, 21.04)) { if (it == "(unnamed)") "via" else "continue" })
        assertTrue(via.toString(), via.isEmpty())
        // A highway named Archer Street through town: straight, so no jog.
        val renamed = reentryOddities(rows(Triple("D'Aguilar Highway", 0.0, 24.9), Triple("Archer Street", 24.9, 25.8), Triple("D'Aguilar Highway", 25.8, 40.0)))
        assertTrue(renamed.toString(), renamed.isEmpty())
        val farAway = reentryOddities(rows(Triple("Main Road", 0.0, 5.0), Triple("Range Road", 5.0, 9.0), Triple("Main Road", 9.0, 12.0)))
        assertTrue(farAway.toString(), farAway.isEmpty())
    }

    @Test
    fun aBackAndForthThroughStreetsIsATangleButAOneRoadHairpinIsNot() {
        // East 150 m, north 60 m, back west 300 m, north 60 m: four streets, ending close to the start.
        val (lats, lons) = walk(90.0 to 1_000.0, 90.0 to 150.0, 0.0 to 60.0, 270.0 to 300.0, 0.0 to 60.0, 270.0 to 100.0, 0.0 to 1_000.0)
        val streets = rows(
            Triple("Main Road", 0.0, 1.0), Triple("Eagle Terrace", 1.0, 1.15), Triple("Skew Street", 1.15, 1.21),
            Triple("Eagle Terrace", 1.21, 1.51), Triple("Skew Street", 1.51, 1.57), Triple("Upper Roma Street", 1.57, 2.67),
        )
        val found = tangleOddities(lats, lons, streets)
        assertEquals(found.toString(), listOf("street tangle"), found.map { it.kind })
        val oneRoad = rows(Triple("Mount Nebo Road", 0.0, 2.67))
        assertTrue(tangleOddities(lats, lons, oneRoad).isEmpty())
    }

    private fun record(planId: String, level: Int, routeKey: String, rating: String?) = JSONObject()
        .put("planId", planId).put("level", level).put("routeKey", routeKey).put("requestKey", "r1")
        .put("rating", rating ?: JSONObject.NULL).put("reasons", JSONArray()).put("note", "")
        .put("algo", JSONObject().put("hash", "a1"))

    @Test
    fun theLatestRatingWinsAClearRemovesItAndBothSurviveAReload() {
        val dir = temp.newFolder("store")
        val store = RaterStore(dir)
        store.rate(record("aaaaaaaaaaaa", 3, "k1", "good"))
        store.rate(record("aaaaaaaaaaaa", 3, "k1", "bad"))
        store.rate(record("aaaaaaaaaaaa", 4, "k1", "good"))
        store.rate(record("aaaaaaaaaaaa", 5, "k2", "good"))
        store.rate(record("aaaaaaaaaaaa", 5, "k2", null))
        val reloaded = RaterStore(dir)
        listOf(store, reloaded).forEach {
            val ratings = it.ratingsFor("aaaaaaaaaaaa")
            assertEquals("bad", ratings.getJSONObject("3#k1").getString("rating"))
            assertEquals("good", ratings.getJSONObject("4#k1").getString("rating"))
            assertFalse(ratings.has("5#k2"))
            assertEquals(2, it.latestRatings().size)
        }
        // Prior ratings for the same request skip the plan being shown.
        assertEquals(0, store.priorFor("r1", "aaaaaaaaaaaa").length())
        assertTrue(store.priorFor("r1", "bbbbbbbbbbbb").has("*#k1"))
    }

    private fun savedPlan(from: String) = JSONObject()
        .put("createdAt", "2026-10-07T00:00:00Z").put("region", JSONObject().put("id", "testland"))
        .put("algo", JSONObject().put("hash", "a1"))
        .put("request", JSONObject().put("from", JSONObject().put("name", from)).put("to", JSONObject().put("name", "B")))

    @Test
    fun aSweepPlanStaysOutOfHistoryUntilSomeonePlansItWithoutATag() {
        val dir = temp.newFolder("tagged")
        val store = RaterStore(dir)
        store.savePlan("aaaaaaaaaaaa", savedPlan("Sweep"), tag = "sweep")
        store.savePlan("bbbbbbbbbbbb", savedPlan("Person"))
        store.indexPlan("aaaaaaaaaaaa", savedPlan("Sweep"), tag = "sweep") // the sweep asks again: still hidden
        fun listed(s: RaterStore, all: Boolean = false) = s.listPlans(includeTagged = all).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("planId") } }
        listOf(store, RaterStore(dir)).forEach {
            assertEquals(listOf("bbbbbbbbbbbb"), listed(it))
            assertEquals(setOf("aaaaaaaaaaaa", "bbbbbbbbbbbb"), listed(it, all = true).toSet())
        }
        store.indexPlan("aaaaaaaaaaaa", savedPlan("Sweep"), tag = null) // a person plans the same request
        store.indexPlan("aaaaaaaaaaaa", savedPlan("Sweep"), tag = "sweep") // a later sweep does not hide it again
        listOf(store, RaterStore(dir)).forEach { assertEquals(listOf("aaaaaaaaaaaa", "bbbbbbbbbbbb"), listed(it)) }
        assertEquals(3, File(dir, "plans.jsonl").readLines().count { it.isNotBlank() })
    }

    @Test
    fun placesInKeepsLocalitiesInsideTheBoxAndNeverStatesOrOtherFeatures() {
        fun doc(name: String, type: Int, subType: Int, rank: Int, lat: Double, lon: Double) =
            TestDoc(name, type, subType, rank, (lat * 1e7).toInt(), (lon * 1e7).toInt(), GeocoderIndex.NO_LOCALITY, "")
        val locality = GeocoderIndex.TYPE_LOCALITY
        val docs = listOf(
            doc("Esk", locality, 1, 222, -27.24, 152.42),
            doc("Toogoolawah", locality, 2, 195, -27.09, 152.38),
            doc("Queensland", locality, 4, 250, -27.10, 152.40),
            doc("Gatton", locality, 1, 224, -27.56, 152.28), // south of the box
            doc("Esk Hotel", GeocoderIndex.TYPE_POI, 0, 100, -27.24, 152.42),
        )
        val index = GeocoderIndex.parse(ByteBuffer.wrap(buildIndex(docs, mapOf("esk" to listOf(0)))).order(ByteOrder.LITTLE_ENDIAN))
        val found = placesIn(index, -27.4, 152.2, -27.0, 152.6)
        val rows = (0 until found.length()).map { found.getJSONObject(it) }
        assertEquals(listOf("Esk" to "town", "Toogoolawah" to "village"), rows.map { it.getString("name") to it.getString("kind") })
        assertEquals(222, rows[0].getInt("rank"))
        assertEquals(-27.24, rows[0].getDouble("lat"), 1e-6)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A `.motomap` with fake graph files; [graphPath]/[graphSha] override the listed graph file. */
    private fun motomap(
        name: String,
        profile: String = RaterRegions.EXPECTED_PROFILE,
        graphPath: String = "properties",
        graphSha: String? = null,
    ): File {
        val properties = "fake graph".toByteArray()
        val geocoder = "OMGEO01\n".toByteArray()
        val manifest = JSONObject()
            .put("packageId", "testland-260101-abcdef123456")
            .put("region", JSONObject().put("id", "testland").put("name", "Testland"))
            .put("source", JSONObject().put("date", "260101"))
            .put("compatibility", JSONObject().put("graphProfile", profile))
            .put(
                "components",
                JSONArray()
                    .put(JSONObject().put("name", "geocoder").put("kind", "file").put("path", "geocoder/geocoder.dat").put("sha256", sha(geocoder)))
                    .put(
                        JSONObject().put("name", "graph").put("kind", "directory").put("path", "graph").put(
                            "files",
                            JSONArray().put(JSONObject().put("path", graphPath).put("sha256", graphSha ?: sha(properties))),
                        ),
                    )
                    .put(JSONObject().put("name", "tiles").put("kind", "file").put("path", "tiles/basemap.pmtiles").put("sha256", "00")),
            )
        val file = File(temp.root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(path: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            put("manifest.json", manifest.toString().toByteArray())
            put("graph/properties", properties)
            put("geocoder/geocoder.dat", geocoder)
            put("tiles/basemap.pmtiles", ByteArray(4))
        }
        return file
    }

    private fun regions() = RaterRegions(temp.newFolder(), temp.newFolder(), "http://127.0.0.1:1")

    @Test
    fun installsOnlyTheGraphAndGeocoderOfAVerifiedPackage() {
        val entry = regions().install(motomap("ok.motomap"))
        assertEquals("testland-260101-abcdef123456", entry.id)
        assertTrue(File(entry.graphDir, "properties").isFile)
        assertTrue(entry.geocoderFile!!.isFile)
        assertFalse(File(entry.graphDir.parentFile, "tiles").exists())
    }

    @Test
    fun refusesUnsafePathsWrongDigestsAndOtherProfiles() {
        listOf(
            motomap("slip.motomap", graphPath = "../../escape"),
            motomap("digest.motomap", graphSha = "0".repeat(64)),
            motomap("profile.motomap", profile = "car|1"),
        ).forEach { file ->
            val regions = regions()
            try {
                regions.install(file)
                fail("${file.name} installed")
            } catch (_: Exception) {
                assertTrue(regions.installed().none { it.source == "package" })
            }
        }
    }
}
