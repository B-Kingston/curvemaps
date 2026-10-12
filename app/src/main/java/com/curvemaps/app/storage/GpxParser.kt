package com.curvemaps.app.storage

import com.curvemaps.app.routing.navigation.GeoMath
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import kotlin.math.ceil

/**
 * Parses GPX 1.0/1.1 documents into an ordered list of [GeoPoint]s.
 *
 * Sources, in priority order:
 *  - every `<trkseg>` of every `<trk>`, concatenated (multi-segment tracks
 *    join; segment gaps are not modelled as route stops);
 *  - otherwise the `<rtept>` points of the first `<rte>` (routed GPX exports
 *    from plotters such as Furkot);
 *  - otherwise the `<wpt>` waypoints (a waypoint list is a valid ride plan).
 *
 * Namespaces are ignored so both the GPX 1.0 and GPX 1.1 namespaces (and
 * namespace-less documents) parse identically. A point with a missing or
 * impossible lat/lon is skipped and counted rather than failing the whole
 * file. Every failure carries a message a rider can act on. The parser never
 * consults the network: it reads the caller-provided [InputStream] only.
 */
object GpxParser {

    /** Which part of the document [GpxRoute.points] came from. */
    enum class Source { TRACK, ROUTE, WAYPOINTS }

    /**
     * One parsed GPX document. [points] is the ride line from [source];
     * [waypoints] are the document's top-level `<wpt>` points (stops, but
     * also points of interest that need not lie on the ride), which are
     * [points] themselves when the file is only a waypoint list.
     * [skippedPoints] counts points dropped for an invalid lat/lon.
     */
    data class GpxRoute(
        val name: String?,
        val points: List<GeoPoint>,
        val waypoints: List<GeoPoint>,
        val source: Source = Source.TRACK,
        val skippedPoints: Int = 0,
    )

    /** Rider-facing failure messages. */
    const val NOT_GPX_MESSAGE = "This file isn't a GPX file."
    const val UNSUPPORTED_VERSION_MESSAGE =
        "This GPX version isn't supported. Export the ride as GPX 1.1 and try again."
    const val NO_RIDE_MESSAGE = "This GPX file has no track, route or waypoints to ride."
    const val DAMAGED_MESSAGE = "This GPX file is damaged or incomplete, so it can't be read."
    const val CANNOT_OPEN_MESSAGE = "That file couldn't be opened."

    /**
     * Parses [input] as GPX.
     *
     * @throws GpxParseException when the stream is not well-formed XML, is not
     *   a GPX document, or contains no usable track/route/waypoint points;
     *   its message is one of the rider-facing messages above and the parser's
     *   own wording is in the cause. The stream is always closed.
     */
    fun parse(input: InputStream): GpxRoute = input.use {
        val parser = try {
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(input, null)
            }
        } catch (e: Exception) {
            throw GpxParseException(NOT_GPX_MESSAGE, e)
        }

        var docName: String? = null
        val trackPoints = ArrayList<GeoPoint>()
        val routePoints = ArrayList<GeoPoint>()
        val wayPoints = ArrayList<GeoPoint>()
        var skipped = 0
        var rootSeen = false
        fun readPointInto(points: MutableList<GeoPoint>) {
            val point = readPoint(parser)
            if (point == null) skipped++ else points.add(point)
        }

        try {
            var insideTrack = false
            var insideRoute = false
            var acceptedRouteSeen = false
            var acceptingRoute = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.END_TAG) {
                    when (parser.name.substringAfter(':')) {
                        "trk" -> insideTrack = false
                        "rte" -> {
                            insideRoute = false
                            acceptingRoute = false
                        }
                    }
                    continue
                }
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val elementName = parser.name.substringAfter(':')
                if (!rootSeen) {
                    if (elementName != "gpx") throw GpxParseException(NOT_GPX_MESSAGE)
                    rootSeen = true
                }
                when (elementName) {
                    "gpx" -> {
                        for (i in 0 until parser.attributeCount) {
                            if (parser.getAttributeName(i) == "version" &&
                                !parser.getAttributeValue(i).startsWith("1")
                            ) {
                                throw GpxParseException(UNSUPPORTED_VERSION_MESSAGE)
                            }
                        }
                    }
                    "trk" -> insideTrack = true
                    "rte" -> {
                        insideRoute = true
                        acceptingRoute = !acceptedRouteSeen
                        acceptedRouteSeen = true
                    }
                    "trkseg" -> check(insideTrack) { "trkseg outside trk" }
                    "trkpt" -> if (insideTrack) readPointInto(trackPoints)
                    "rtept" -> if (insideRoute && acceptingRoute) readPointInto(routePoints)
                    "wpt" -> if (!insideTrack && !insideRoute) readPointInto(wayPoints)
                    "name" -> if (docName == null && !insideTrack && !insideRoute) {
                        // readPoint advances past END_TAG, so a name child
                        // captured here must be read with nextText().
                        docName = parser.nextText().trim()
                    }
                }
            }
        } catch (e: GpxParseException) {
            throw e
        } catch (e: Exception) {
            // Bytes that never reached a <gpx> root are some other file (a
            // PDF, a web page); a GPX that breaks off later is damaged.
            throw GpxParseException(if (rootSeen) DAMAGED_MESSAGE else NOT_GPX_MESSAGE, e)
        }
        if (!rootSeen) throw GpxParseException(NOT_GPX_MESSAGE)

        val (source, points) = when {
            trackPoints.size >= 2 -> Source.TRACK to trackPoints
            routePoints.size >= 2 -> Source.ROUTE to routePoints
            else -> Source.WAYPOINTS to wayPoints
        }
        if (points.size < 2) throw GpxParseException(NO_RIDE_MESSAGE)
        GpxRoute(docName?.takeIf { it.isNotBlank() }, points, wayPoints, source, skipped)
    }

    /**
     * Reads one lat/lon point, or null when its lat/lon is missing or
     * impossible; child elements (<ele>, <time>, extensions) are skipped.
     */
    private fun readPoint(parser: XmlPullParser): GeoPoint? {
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
        // Consume the element so its children (elevation, time, heart rate…)
        // are never mistaken for sibling points.
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            // skip
        }
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0
        ) {
            return null
        }
        return GeoPoint(lat, lon)
    }

    /** Thrown for every parse failure with a user-presentable message. */
    class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
}

/**
 * Geometric helpers shared by the GPX import path: haversine length in metres
 * (same spherical mean-radius model as [RouteSimilarity]) and point thinning
 * that keeps the encoded polyline small while preserving shape.
 */
object GpxGeometry {

    /** Great-circle distance in metres on a spherical Earth (mean radius). */
    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double =
        GeoMath.haversineM(a.lat, a.lon, b.lat, b.lon)

    /** Summed great-circle length of the polyline in metres. */
    fun lengthMeters(points: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += haversineMeters(points[i - 1], points[i])
        return total
    }

    /**
     * Removes points closer than [minSpacingMeters] to the previously kept
     * point; the first and last points are always kept. A GPX track logs a
     * point every few metres; encoding thousands of them inflates storage and
     * slows the similarity match for zero visual gain.
     */
    fun thin(points: List<GeoPoint>, minSpacingMeters: Double = 20.0): List<GeoPoint> {
        if (points.size <= 2) return points
        val out = ArrayList<GeoPoint>(points.size)
        out.add(points.first())
        for (i in 1 until points.size - 1) {
            if (haversineMeters(out.last(), points[i]) >= minSpacingMeters) out.add(points[i])
        }
        // The final point is a hard endpoint; drop kept points that sit
        // closer than the spacing to the end, never the end itself.
        while (out.size > 1 && haversineMeters(out.last(), points.last()) < minSpacingMeters) {
            out.removeAt(out.size - 1)
        }
        out.add(points.last())
        return out
    }

    /**
     * Produces bounded GraphHopper via points from a detailed GPX line.
     *
     * Routing only the two endpoints allows the engine to replace the whole
     * imported ride with its own route. Instead, this samples the line at a
     * maximum along-track spacing and merges explicit GPX stops at their
     * nearest position in the track. This retains loops and produces normal
     * turn instructions for every leg without submitting thousands of GPS
     * recording points to the router.
     */
    fun routingPoints(
        points: List<GeoPoint>,
        waypoints: List<GeoPoint> = emptyList(),
        maxSpacingMeters: Double = 1_500.0,
    ): List<GeoPoint> {
        require(points.size >= 2) { "A route needs at least two points" }
        require(maxSpacingMeters > 0.0) { "Routing-point spacing must be positive" }

        // Sample by cumulative along-track distance. The hard point cap also
        // applies when the source itself has unusually sparse, long segments.
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + haversineMeters(points[i - 1], points[i])
        }
        val closedLoop = haversineMeters(points.first(), points.last()) < LOOP_THRESHOLD_METERS
        val minimumSamples = if (closedLoop) 3 else 2
        val sampleCount = (ceil(cumulative.last() / maxSpacingMeters).toInt() + 1)
            .coerceIn(minimumSamples, MAX_ROUTING_POINTS)
        val indexed = ArrayList<IndexedRoutingPoint>()
        var sourceIndex = 0
        for (sample in 0 until sampleCount) {
            val targetDistance = cumulative.last() * sample / (sampleCount - 1)
            while (sourceIndex < points.lastIndex && cumulative[sourceIndex] < targetDistance) {
                sourceIndex++
            }
            if (indexed.lastOrNull()?.index != sourceIndex) {
                indexed += IndexedRoutingPoint(sourceIndex, points[sourceIndex], false)
            }
        }
        if (indexed.last().index != points.lastIndex) {
            indexed += IndexedRoutingPoint(points.lastIndex, points.last(), false)
        }

        // GPX waypoints are ordered stops. Place each beside the closest track
        // vertex so it remains in the correct position even for closed loops.
        for (waypoint in waypoints) {
            var nearestIndex = 0
            var nearestDistance = Double.POSITIVE_INFINITY
            for (i in points.indices) {
                val distance = haversineMeters(waypoint, points[i])
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearestIndex = i
                }
            }
            indexed += IndexedRoutingPoint(nearestIndex, waypoint, true)
        }
        indexed.sortWith(compareBy<IndexedRoutingPoint> { it.index }.thenBy { !it.explicit })

        val result = ArrayList<GeoPoint>(indexed.size)
        for (candidate in indexed) {
            if (result.isNotEmpty() &&
                haversineMeters(result.last(), candidate.point) < DUPLICATE_POINT_METERS
            ) {
                // Prefer an explicit stop over a nearby sampled track point.
                if (candidate.explicit) result[result.lastIndex] = candidate.point
            } else {
                result += candidate.point
            }
        }
        // Endpoints own the route boundary. Nearby standalone waypoints may
        // shape the first/last leg, but may never replace the actual GPX ends.
        if (haversineMeters(result.first(), points.first()) < DUPLICATE_POINT_METERS) {
            result[0] = points.first()
        } else {
            result.add(0, points.first())
        }
        if (haversineMeters(result.last(), points.last()) < DUPLICATE_POINT_METERS) {
            result[result.lastIndex] = points.last()
        } else {
            result += points.last()
        }
        require(result.size >= 2) { "GPX route has no usable distance" }
        return if (result.size <= MAX_ROUTING_POINTS) result else {
            // A file can contain arbitrarily many explicit waypoints. Keep the
            // request within GraphHopper's practical bound while retaining
            // both ends and sampling the ordered merged list evenly.
            List(MAX_ROUTING_POINTS) { slot ->
                result[(slot.toLong() * result.lastIndex / (MAX_ROUTING_POINTS - 1)).toInt()]
            }
        }
    }

    private data class IndexedRoutingPoint(
        val index: Int,
        val point: GeoPoint,
        val explicit: Boolean,
    )

    /** Endpoints closer than this are treated as a closed loop (e.g. a return-to-start ride). */
    const val LOOP_THRESHOLD_METERS = 150.0

    private const val MAX_ROUTING_POINTS = 250
    private const val DUPLICATE_POINT_METERS = 10.0

}
