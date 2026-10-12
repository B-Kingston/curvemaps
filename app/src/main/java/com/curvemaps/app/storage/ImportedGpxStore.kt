package com.curvemaps.app.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An imported GPX ride as the planner holds it. */
data class ImportedGpx(
    val name: String,
    val plan: GpxRidePlan,
    val milestones: List<GpxMilestone>,
)

/**
 * Keeps the imported GPX ride the planner is showing, so rotation, process
 * death and Back → START never lose it: the planner's saved state only says
 * an import is open, and the ride itself (a track can hold tens of thousands
 * of points, far beyond what saved state may carry) lives in one private file.
 * Writes go through a temporary file and a rename, so a crash mid-write
 * leaves the previous import or none, never half of one.
 */
class ImportedGpxStore(private val file: File) {

    fun save(ride: ImportedGpx) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(encode(ride))
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "Could not store the imported GPX ride" }
        }
    }

    /** The stored ride, or null when there is none or it cannot be read. */
    fun load(): ImportedGpx? = try {
        if (file.isFile) decode(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun clear() {
        file.delete()
    }

    internal companion object {
        private const val VERSION = 1

        fun encode(ride: ImportedGpx): String = JSONObject()
            .put("version", VERSION)
            .put("name", ride.name)
            .put("mode", ride.plan.mode.name)
            .put("line", PolylineCodec.encode(ride.plan.line))
            .put("stops", PolylineCodec.encode(ride.plan.stops))
            .put("ignoredWaypoints", ride.plan.ignoredWaypoints)
            .put(
                "milestones",
                JSONArray(
                    ride.milestones.map { milestone ->
                        JSONObject()
                            .put("lat", milestone.point.lat)
                            .put("lon", milestone.point.lon)
                            .put("distance", milestone.distanceMeters)
                            .put("progress", milestone.progress)
                            .putOpt("placeName", milestone.placeName)
                            .putOpt("placeDetail", milestone.placeDetail)
                    },
                ),
            )
            .toString()

        fun decode(text: String): ImportedGpx? {
            val json = JSONObject(text)
            if (json.optInt("version") != VERSION) return null
            val line = PolylineCodec.decode(json.getString("line"))
            if (line.size < 2) return null
            val milestones = json.getJSONArray("milestones").let { array ->
                List(array.length()) { i ->
                    val item = array.getJSONObject(i)
                    GpxMilestone(
                        point = GeoPoint(item.getDouble("lat"), item.getDouble("lon")),
                        distanceMeters = item.getDouble("distance"),
                        progress = item.getDouble("progress"),
                        placeName = item.optString("placeName").takeIf { item.has("placeName") },
                        placeDetail = item.optString("placeDetail").takeIf { item.has("placeDetail") },
                    )
                }
            }
            return ImportedGpx(
                name = json.getString("name"),
                plan = GpxRidePlan(
                    mode = GpxRideMode.valueOf(json.getString("mode")),
                    line = line,
                    stops = PolylineCodec.decode(json.getString("stops")),
                    ignoredWaypoints = json.optInt("ignoredWaypoints"),
                ),
                milestones = milestones,
            )
        }
    }
}
