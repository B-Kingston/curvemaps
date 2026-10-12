package com.curvemaps.app.storage

import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.buildGhRequest
import com.curvemaps.app.routing.loadMotoGraphHopper
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.shapes.GHPoint
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Opt-in desktop probe for following imported GPX tracks on the real graph.
 *
 * Each corridor is ridden once at a twisty style to make a "recorded" track
 * (a point every ~15 m with ±6 m of GPS noise, like a phone log), then
 * imported two ways and measured against that track ([GpxTrackFollow.measure]):
 * the old import (a shaping point every 1.5 km, routed at the rider's style,
 * no headings) and the followed track ([GpxTrackFollow.follow], what the app
 * now does). Prints one `GPX_FOLLOW` line per import and fails if a followed
 * track misses more than 1% of its line or adds more than 1% off it. Skipped
 * unless `GPX_FOLLOW=1`:
 *
 * ```
 * GPX_FOLLOW=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *   --tests '*GpxFollowProbeTest*' -i | grep GPX_FOLLOW
 * ```
 *
 * Measured on the Queensland graph (6 corridors, 4–132 km): the old import
 * left up to 4.7 km of a track unridden and drove up to 19 km off it (spurs to
 * points on a motorway's other carriageway); followed tracks miss nothing on
 * any corridor and stray at most 0.3%, in at most 3 rounds and 100 ms on the
 * desktop.
 *
 * With `GPX_FIXTURE_DIR=<dir>` it also writes each recorded track as a GPX
 * file there (the device tests and `tools/test/visual.py gpx` use the one
 * committed as `app/src/androidTest/assets/gpx/mount-glorious-loop.gpx`).
 */
class GpxFollowProbeTest {

    private data class Corridor(val name: String, val style: RideStyle, val stops: List<GHPoint>)

    @Test
    fun probe() {
        val graphDir = listOf(File("src/main/assets/graph-cache"), File("../data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
        assumeTrue(System.getenv("GPX_FOLLOW") != null && graphDir != null)
        val fixtureDir = System.getenv("GPX_FIXTURE_DIR")?.let(::File)
        val hopper = loadMotoGraphHopper(graphDir!!)
        try {
            CORRIDORS.forEach { corridor ->
                val ridden = route(hopper, corridor.stops, corridor.style)
                val recorded = record(ridden.points, Random(corridor.name.hashCode()))
                val plan = GpxTrackFollow.plan(
                    GpxParser.GpxRoute(corridor.name, recorded, emptyList(), GpxParser.Source.TRACK),
                )
                check(plan.mode == GpxRideMode.FOLLOW_TRACK)
                val length = GpxGeometry.lengthMeters(plan.line)

                // The old import: 1.5 km shaping points at the rider's style
                // (Fastest, the slider's default, and Max twisty).
                val old = GpxGeometry.routingPoints(GpxGeometry.thin(recorded))
                listOf(RideStyle.FASTEST, RideStyle.MAX_TWISTY).forEach { style ->
                    val oldStarted = System.nanoTime()
                    val oldPath = route(hopper, old.map { GHPoint(it.lat, it.lon) }, style)
                    val oldMeasure = GpxTrackFollow.measure(plan.line, oldPath.points.geo())
                    report(corridor.name, "old@${style.name.lowercase()}", length, oldMeasure, 1, old.size, System.nanoTime() - oldStarted)
                }

                val started = System.nanoTime()
                val result = GpxTrackFollow.follow(plan.line, GpxTrackFollow.shapingPoints(plan.line, plan.stops)) { shaping ->
                    val vias = shaping.subList(1, shaping.size - 1)
                    val request = buildGhRequest(
                        from = GHPoint(shaping.first().point.lat, shaping.first().point.lon),
                        to = GHPoint(shaping.last().point.lat, shaping.last().point.lon),
                        style = RideStyle.FASTEST,
                        blockUnpaved = false,
                        avoidMotorways = false,
                        attempt = 0,
                        siblingEdgeIds = emptySet(),
                        viaPoints = vias.map { GHPoint(it.point.lat, it.point.lon) },
                        alternatives = false,
                        viaHeadings = vias.map { it.headingDeg },
                    )
                    val response = hopper.route(request)
                    check(!response.hasErrors()) { response.errors.toString() }
                    val path = response.best
                    FollowAttempt(path, path.points.geo(), path.waypoints.geo())
                }
                report(
                    corridor.name, "followed", length, result.measure, result.attempts, result.shaping.size,
                    System.nanoTime() - started,
                )
                assertTrue(
                    "${corridor.name}: followed track misses ${result.measure.offTrackMeters} m of $length m",
                    result.measure.offTrackMeters <= 0.01 * length,
                )
                assertTrue(
                    "${corridor.name}: followed track adds ${result.measure.extraMeters} m off it",
                    result.measure.extraMeters <= 0.01 * length,
                )
                fixtureDir?.let { dir ->
                    dir.mkdirs()
                    File(dir, "${corridor.name}.gpx").writeText(gpx(corridor.name, recorded, FIXTURE_WAYPOINTS[corridor.name].orEmpty()))
                }
            }
        } finally {
            hopper.close()
        }
    }

    private fun route(hopper: GraphHopper, points: List<GHPoint>, style: RideStyle): ResponsePath {
        val request = buildGhRequest(
            from = points.first(),
            to = points.last(),
            style = style,
            blockUnpaved = false,
            avoidMotorways = false,
            attempt = 0,
            siblingEdgeIds = emptySet(),
            viaPoints = points.subList(1, points.size - 1),
            alternatives = false,
        )
        val response = hopper.route(request)
        check(!response.hasErrors()) { response.errors.toString() }
        return response.best
    }

    /** A phone's log of riding [line]: a point every ~15 m, each a few metres off. */
    private fun record(line: PointList, random: Random): List<GeoPoint> =
        GpxTrackFollow.densify(line.geo(), 15.0).map {
            GeoPoint(it.lat + random.nextDouble(-6.0, 6.0) / 111_132.0, it.lon + random.nextDouble(-6.0, 6.0) / 99_000.0)
        }

    private fun report(
        name: String,
        how: String,
        length: Double,
        measure: TrackFollowMeasure,
        rounds: Int,
        points: Int,
        nanos: Long,
    ) = println(
        "GPX_FOLLOW %-22s %-15s %6.1f km  track not ridden %6.0f m (%4.1f%%)  off the track %6.0f m  rounds %d  points %3d  %5d ms".format(
            Locale.US, name, how, length / 1_000, measure.offTrackMeters, 100 * measure.offTrackMeters / length,
            measure.extraMeters, rounds, points, nanos / 1_000_000,
        ),
    )

    private fun gpx(name: String, track: List<GeoPoint>, waypoints: List<Pair<String, GeoPoint>>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"CurveMaps GpxFollowProbeTest\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata><name>").append(name).append("</name></metadata>\n")
        waypoints.forEach { (label, p) ->
            append("  <wpt lat=\"%.6f\" lon=\"%.6f\"><name>%s</name></wpt>\n".format(Locale.US, p.lat, p.lon, label))
        }
        append("  <trk><name>").append(name).append("</name><trkseg>\n")
        track.forEach { append("    <trkpt lat=\"%.6f\" lon=\"%.6f\"/>\n".format(Locale.US, it.lat, it.lon)) }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    private fun PointList.geo(): List<GeoPoint> = List(size()) { GeoPoint(getLat(it), getLon(it)) }

    private companion object {
        val CORRIDORS = listOf(
            // Range roads where the fastest road between 1.5 km points is
            // often not the ridden one.
            Corridor(
                "mount-glorious-loop",
                RideStyle.MAX_TWISTY,
                listOf(
                    GHPoint(-27.3717, 152.8867), // Samford
                    GHPoint(-27.3311, 152.7614), // Mount Glorious
                    GHPoint(-27.3963, 152.7843), // Mount Nebo
                    GHPoint(-27.3717, 152.8867),
                ),
            ),
            Corridor(
                "palmwoods-kenilworth",
                RideStyle.TWISTY,
                listOf(GHPoint(-26.6886, 152.9600), GHPoint(-26.5960, 152.7290)),
            ),
            // Divided city roads, where a shaping point can snap onto the
            // opposite carriageway.
            Corridor(
                "brisbane-toowong",
                RideStyle.FASTEST,
                listOf(GHPoint(-27.4698, 153.0251), GHPoint(-27.4847, 152.9930)),
            ),
            Corridor(
                "brisbane-gold-coast",
                RideStyle.FASTEST,
                listOf(GHPoint(-27.4698, 153.0251), GHPoint(-28.0023, 153.4145)),
            ),
            // A long range ride with towns, a highway stretch and switchbacks.
            Corridor(
                "brisbane-toowoomba",
                RideStyle.MAX_TWISTY,
                listOf(GHPoint(-27.4698, 153.0251), GHPoint(-27.5598, 151.9507)),
            ),
            // Hinterland back roads through a small town.
            Corridor(
                "maleny-kenilworth",
                RideStyle.VERY_TWISTY,
                listOf(GHPoint(-26.7598, 152.8510), GHPoint(-26.5960, 152.7290)),
            ),
        )

        /** Waypoints written into the fixtures: one on the ride, one beside it. */
        val FIXTURE_WAYPOINTS = mapOf(
            "mount-glorious-loop" to listOf(
                "Mount Glorious cafe stop" to GeoPoint(-27.3311, 152.7614),
                "Lookout off the ride" to GeoPoint(-27.3000, 152.7000),
            ),
        )
    }
}
