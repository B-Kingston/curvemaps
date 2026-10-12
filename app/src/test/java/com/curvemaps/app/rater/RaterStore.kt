package com.curvemaps.app.rater

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The rater's files under one directory (default `data/rater/`):
 *
 * - `plans/<planId>.json`: a full plan (request, region, code version, the
 *   seven stops, every candidate search, features and geometry per route).
 * - `plans.jsonl`: one summary line per saved plan. A plan saved with a tag
 *   (an agent sweep) is left out of History until someone plans the same
 *   request without one, which appends an untagged line.
 * - `ratings.jsonl`: append-only; one self-contained line per rating (request,
 *   region, code version, the stop it was rated for, the route's stop meta
 *   and features). The latest line per (plan, stop, route) wins, and
 *   `"rating": null` clears one.
 *
 * Ratings are keyed by the stop as well as the route: the same road can be
 * right for Twisty and wrong for Mostly fast.
 */
internal class RaterStore(val dir: File) {
    private val plansDir = File(dir, "plans").apply { mkdirs() }
    private val plansIndex = File(dir, "plans.jsonl")
    val ratingsFile = File(dir, "ratings.jsonl")

    /** "planId#level#routeKey" → latest record. */
    private val latest = LinkedHashMap<String, JSONObject>()

    /** planId → the tag of its latest summary line (null: listed in History). */
    private val planTags = HashMap<String, String?>()

    init {
        if (ratingsFile.isFile) ratingsFile.forEachLine { line ->
            if (line.isNotBlank()) runCatching { JSONObject(line) }.getOrNull()?.let { index(it) }
        }
        if (plansIndex.isFile) plansIndex.forEachLine { line ->
            if (line.isNotBlank()) runCatching { JSONObject(line) }.getOrNull()?.let {
                planTags[it.getString("planId")] = it.optString("tag").ifBlank { null }
            }
        }
    }

    private fun index(record: JSONObject) {
        val key = "${record.getString("planId")}#${record.getInt("level")}#${record.getString("routeKey")}"
        latest.remove(key)
        latest[key] = record
    }

    @Synchronized
    fun savePlan(planId: String, plan: JSONObject, tag: String? = null) {
        File(plansDir, "$planId.json").writeText(plan.toString())
        indexPlan(planId, plan, tag)
    }

    /**
     * Adds the plan's History summary line: once per plan, tagged when [tag] is set, and once more, untagged,
     * when a tagged plan is requested without a tag (a person opening a request a sweep planned).
     */
    @Synchronized
    fun indexPlan(planId: String, plan: JSONObject, tag: String? = null) {
        if (planId in planTags && (tag != null || planTags[planId] == null)) return
        val request = plan.getJSONObject("request")
        plansIndex.appendText(
            JSONObject()
                .put("planId", planId)
                .put("createdAt", plan.getString("createdAt"))
                .put("region", plan.getJSONObject("region").getString("id"))
                .put("algo", plan.getJSONObject("algo").getString("hash"))
                .put("from", request.getJSONObject("from").optString("name"))
                .put("to", request.getJSONObject("to").optString("name"))
                .put("request", request)
                .apply { if (tag != null) put("tag", tag) }
                .toString() + "\n",
        )
        planTags[planId] = tag
    }

    fun loadPlan(planId: String): JSONObject? {
        require(Regex("[0-9a-f]{12}").matches(planId)) { "Bad plan id" }
        return File(plansDir, "$planId.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
    }

    /** Newest first; plans whose latest line is tagged only with [includeTagged]. */
    @Synchronized
    fun listPlans(limit: Int = 200, includeTagged: Boolean = false): JSONArray {
        if (!plansIndex.isFile) return JSONArray()
        val counts = HashMap<String, IntArray>()
        latest.values.forEach { r ->
            val c = counts.getOrPut(r.getString("planId")) { IntArray(2) }
            when (r.optString("rating")) {
                "good" -> c[0]++
                "bad" -> c[1]++
            }
        }
        val lines = plansIndex.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        val out = JSONArray()
        lines.asReversed().distinctBy { it.getString("planId") }.filter { includeTagged || !it.has("tag") }.take(limit).forEach {
            val c = counts[it.getString("planId")] ?: IntArray(2)
            out.put(it.put("good", c[0]).put("bad", c[1]))
        }
        return out
    }

    @Synchronized
    fun rate(record: JSONObject) {
        ratingsFile.appendText(record.toString() + "\n")
        index(record)
    }

    /** Latest non-cleared ratings, oldest first. */
    @Synchronized
    fun latestRatings(): List<JSONObject> = latest.values.filter { it.opt("rating") is String }

    /** "level#routeKey" → {rating, reasons, note} for one plan. */
    @Synchronized
    fun ratingsFor(planId: String): JSONObject {
        val out = JSONObject()
        latest.values.filter { it.getString("planId") == planId && it.opt("rating") is String }.forEach {
            out.put("${it.getInt("level")}#${it.getString("routeKey")}", brief(it))
        }
        return out
    }

    /**
     * Ratings of the same request (any code version) by "level#routeKey",
     * plus "*#routeKey" for a route rated at some other stop.
     */
    @Synchronized
    fun priorFor(requestKey: String, excludePlanId: String?): JSONObject {
        val out = JSONObject()
        latest.values.filter { it.getString("requestKey") == requestKey && it.getString("planId") != excludePlanId && it.opt("rating") is String }
            .forEach {
                val b = brief(it).put("planId", it.getString("planId")).put("algo", it.getJSONObject("algo").getString("hash"))
                out.put("${it.getInt("level")}#${it.getString("routeKey")}", b)
                out.put("*#${it.getString("routeKey")}", b.put("level", it.getInt("level")))
            }
        return out
    }

    private fun brief(r: JSONObject) = JSONObject()
        .put("rating", r.getString("rating"))
        .put("reasons", r.optJSONArray("reasons") ?: JSONArray())
        .put("note", r.optString("note"))
}
