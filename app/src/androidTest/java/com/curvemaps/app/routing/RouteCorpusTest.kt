package com.curvemaps.app.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.curvemaps.app.storage.GeoPoint
import com.curvemaps.app.storage.PolylineCodec
import com.curvemaps.app.storage.RouteSimilarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The corridors [RouteCorpusTest.everyStopBecomesItsOwnRideOnTheRealGraph]
 * resolves all seven stops for. The last one is the corridor that used to
 * return the same ride for five of its seven stops.
 */
private val DISTINCT_RIDE_CORRIDORS = listOf(
    "Brisbane CBD to Mount Glorious",
    "Samford to Brisbane CBD",
    "Toowoomba to Warwick",
    "Palmwoods to Kenilworth",
    "Skyline Circuit to Palmwoods",
    "Parkpine Drive to Dulong",
)

/** Round-trip starts: hinterland, city and coast ([RouteCorpusTest.roundTripsAreSevenDifferentLoopsBackToTheStart]). */
private val ROUND_TRIP_STARTS = listOf(
    "Samford" to com.graphhopper.util.shapes.GHPoint(-27.3727, 152.8864),
    // A loop street; its 50 km Max twisty used to lasso a corner off the Mooloolah road.
    "Skyline Circuit" to com.graphhopper.util.shapes.GHPoint(-26.6787181, 152.9698004),
    "Brisbane CBD" to com.graphhopper.util.shapes.GHPoint(-27.4698, 153.0251),
    "Noosa" to com.graphhopper.util.shapes.GHPoint(-26.3980, 153.0600),
)

@RunWith(AndroidJUnit4::class)
class RouteCorpusTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 300_000)
    fun scenicDiversionProbeUsesTheShippedGraph() = kotlinx.coroutines.runBlocking {
        val router = GraphHopperRouter(context)
        val entry = ROUTE_CORPUS.first { it.name == "Samford to southern Brisbane scenic suggestion corridor" }
        val fastest = router.route(entry.from, entry.to, 0.0).routes.first()
        android.util.Log.i("CurveMaps.Corpus", "DIVERSION_GOLD distance=${fastest.distance} duration=${fastest.time}")
        val baseline = com.curvemaps.app.routing.navigation.RouteTrackFactory.fromPath(
            router.route(entry.from, entry.to, RideStyle.BALANCED.level.toDouble()).routes.first())
        val offset = 15_050.0
        val window = com.curvemaps.app.routing.suggestion.SuggestionWindow.choose(
            baseline.totalDistanceM, offset, 15.0)!!
        val position = baseline.positionAt(offset)
        val request = com.curvemaps.app.routing.suggestion.DiversionSearchRequest(
            1, 1, baseline, offset, position.first, position.second,
            window.first, window.second, 15.0, RideStyle.BALANCED)
        val search = com.curvemaps.app.RideSuggestionSearch()
        try {
            val proposal = search.search(router, request, entry.to, false, false)
            org.junit.Assert.assertNotNull("The committed corridor must produce a genuine offer", proposal)
            proposal!!
            assertTrue(com.curvemaps.app.routing.suggestion.DiversionScorer.qualifies(proposal.measure))
            assertTrue(proposal.viaPoints.size in 2..8)
            val path = proposal.path!!
            // Measured on the same basis as the baseline: route tracks sum flat haversine metres, while
            // GraphHopper's path.distance includes the climb on this 3D graph (11 m apart on a hilly offer).
            val replacement = com.curvemaps.app.routing.navigation.RouteTrackFactory.fromPath(path)
            assertEquals(replacement.totalDistanceM - baseline.remainingDistanceM(offset), proposal.measure.extraDistanceM, 2.0)
            assertEquals(path.time / 1000.0 - baseline.remainingTimeS(offset), proposal.measure.extraTimeS, 0.01)
            assertTrue(com.curvemaps.app.routing.suggestion.SuggestionWindow.divergenceLeadOk(
                proposal.span.divergeReplacementOffsetM, 15.0))
            android.util.Log.i("CurveMaps.Corpus", "DIVERSION_PROBE ${proposal.measure}")
            Unit
        } finally { search.close() }
    }

    @Test(timeout = 300_000)
    fun corpusRoutesAreValidAtEachRideStyle() = assertCorpusRoutes(ROUTE_CORPUS.take(5))

    @Test(timeout = 300_000)
    fun remainingCorpusRoutesAreValidAtEachRideStyle() = assertCorpusRoutes(ROUTE_CORPUS.drop(5).take(5))

    private fun assertCorpusRoutes(entries: List<RouteCorpusEntry>) {
        val router = GraphHopperRouter(context)
        entries.forEach { entry ->
            RideStyle.entries.map { it.level }.forEach { detent ->
                val result = router.route(entry.from, entry.to, detent.toDouble())
                // The planner shows one route per slider stop.
                assertEquals("${entry.name} detent $detent", 1, result.routes.size)
                result.routes.forEachIndexed { routeIndex, path ->
                    assertTrue("${entry.name} has too few points", path.points.size() >= 2)
                    assertTrue("${entry.name} has no distance", path.distance > 0.0)
                    assertTrue("${entry.name} has no duration", path.time > 0L)
                    val roadClasses = path.pathDetails["road_class"].orEmpty()
                    assertTrue("${entry.name} must provide road classes for full-width ride highlighting", roadClasses.isNotEmpty())
                    assertEquals(0, roadClasses.first().first)
                    assertEquals(path.points.size() - 1, roadClasses.last().last)
                    assertTrue(roadClasses.all { it.last > it.first })
                    assertTrue(roadClasses.zipWithNext().all { (before, after) -> before.last == after.first })
                    val points = path.points.toGeoPoints()
                    assertTrue(points.all { point ->
                        point.lat.isFinite() && point.lon.isFinite() &&
                            point.lat in -90.0..90.0 && point.lon in -180.0..180.0
                    })
                    assertTrue(RouteSimilarity.haversineMeters(points.first(), entry.from.toGeoPoint()) <= 5_000.0)
                    assertTrue(RouteSimilarity.haversineMeters(points.last(), entry.to.toGeoPoint()) <= 5_000.0)
                    assertRoundTrip(points)
                    val distanceKm = path.distance / 1_000.0
                    assertTrue(
                        "${entry.name} detent $detent route $routeIndex distance ${distanceKm}km",
                        distanceKm in entry.baselineKm * 0.5..entry.baselineKm * 2.5,
                    )
                    val durationHours = path.time / 3_600_000.0
                    assertTrue(
                        "${entry.name} detent $detent route $routeIndex duration ${durationHours}h",
                        durationHours in entry.baselineHours * 0.4..entry.baselineHours * 4.0,
                    )
                }
            }
        }
    }

    @Test(timeout = 120_000)
    fun identicalEndpointsAreRejectedCleanly() {
        val entry = ROUTE_CORPUS[10]
        val failure = runCatching { GraphHopperRouter(context).route(entry.from, entry.to) }.exceptionOrNull()
        assertTrue("expected identical endpoints to fail, got $failure", failure is IllegalStateException)
        assertFalse(failure!!.message.orEmpty().contains("OutOfMemory"))
    }

    @Test(timeout = 120_000)
    fun outOfRegionIsRejectedCleanly() {
        val entry = ROUTE_CORPUS[11]
        val failure = runCatching { GraphHopperRouter(context).route(entry.from, entry.to) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("Routing failed"))
    }

    @Test(timeout = 300_000)
    fun alternativesStayWithinTheSiblingShareAtEveryStyle() {
        // Alternatives now serve ride suggestions and saved-ride matching only.
        val router = GraphHopperRouter(context)
        ROUTE_CORPUS.filter { it.alternativesExpected }.forEach { entry ->
            RideStyle.entries.forEach { style ->
                val routes = router.route(entry.from, entry.to, style.level.toDouble(), alternatives = true).routes
                if (style == RideStyle.TWISTY) {
                    assertTrue("${entry.name} must offer alternatives at ${style.label}", routes.size >= 2)
                }
                for (i in routes.indices) {
                    assertTrue(routes[i].points.size() >= 2)
                    for (j in i + 1 until routes.size) {
                        val left = AlternativePolicy.cachedRoute(routes[i])
                        val right = AlternativePolicy.cachedRoute(routes[j])
                        assertTrue(
                            "${entry.name} ${style.label} cards $i/$j share too many roads",
                            AlternativePolicy.sharedRoadFraction(left.edgeDistances, right.edgeDistances) <=
                                AlternativePolicy.SIBLING_MAX_SHARE + 1e-9,
                        )
                    }
                }
            }
        }
    }

    /**
     * The planner turns the seven stops into seven different rides
     * ([DistinctRideStyles]): a stop whose optimum repeats a lower stop's ride
     * is searched again a little curvier, then with bounded road penalties,
     * then by looking around the corridor through via points on through
     * roads, until it finds a ride no lower stop offers. On the real graph
     * every stop must end up a meaningfully different ride — no duplicate, no
     * tiny road swap, no out-and-back spur — never less curvy than asked, and
     * within its detour budget except where the search had to widen (the
     * 2 km hop, whose options say so). STYLE_DISTINCT lines record each
     * stop's ride, search round and weight per corridor.
     */
    @Test(timeout = 1_200_000)
    fun everyStopBecomesItsOwnRideOnTheRealGraph() {
        val router = GraphHopperRouter(context)
        DISTINCT_RIDE_CORRIDORS.map { name -> ROUTE_CORPUS.single { it.name == name } }.forEach { entry ->
            val options = runBlocking {
                DistinctRideStyles.resolve(
                    optimum = { style ->
                        router.route(entry.from, entry.to, style.level.toDouble()).routes.single()
                    },
                    reweighted = { style, weight ->
                        router.route(entry.from, entry.to, style.level.toDouble(), curveWeight = weight).routes.single()
                    },
                    diversified = { style, search ->
                        try {
                            router.route(
                                entry.from,
                                entry.to,
                                style.level.toDouble(),
                                viaPoints = listOfNotNull(search.via),
                                curveWeight = search.curveWeight,
                                penalizedEdges = search.penalizedEdges,
                                roadPenalty = search.roadPenalty,
                                passThrough = search.via != null,
                            ).routes.single()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    },
                    rings = DistinctRideStyles.detourRings(entry.from, entry.to),
                )
            }
            println(
                "STYLE_DISTINCT ${entry.name} " +
                    RideStyle.entries.joinToString(" ") { style ->
                        val option = options.getValue(style)
                        val path = option.path
                        "${style.label}=${(path.distance / 1000).toInt()}km/${path.time / 60_000}min/" +
                            "${(curvyDistanceFraction(path.points) * 100).toInt()}%" +
                            "(r${option.round}" +
                            (if (option.curveWeight > style.curveWeight) " w${"%.2f".format(option.curveWeight)}" else "") +
                            (if (option.roadPenalty > 0.0) " p${"%.2f".format(option.roadPenalty)}" else "") +
                            (if (option.via != null) " via" else "") +
                            (if (option.budget != DistinctRideStyles.Budget.STYLE) " ${option.budget}" else "") +
                            ")"
                    },
            )
            val shown = options.mapValues { AlternativePolicy.cachedRoute(it.value.path) }
            RideStyle.entries.forEach { style ->
                val option = options.getValue(style)
                val optimum = router.route(entry.from, entry.to, style.level.toDouble()).routes.single()
                // Every stop is a ride of its own: never a repeat, never an
                // out-and-back spur, and within its budget unless the search
                // had to widen (a 2 km hop) and says so.
                assertNull("${entry.name} ${style.label} repeats a stop", option.repeats)
                assertFalse("${entry.name} ${style.label} drives a road twice", DistinctRideStyles.drivesARoadTwice(shown.getValue(style)))
                assertFalse("${entry.name} ${style.label} turns around", DistinctRideStyles.turnsAround(option.path))
                // Never off the road through a slip lane or junction triangle and straight back on.
                val jogs = router.junctionJogs(option.path)
                assertTrue("${entry.name} ${style.label} jogs through a junction: $jogs", jogs.isEmpty())
                // A ride never prefers straighter roads than the search that found it: this stop's own, or a lower
                // stop's when the joint choice moved that ride up (Option.foundBy).
                val searchedBy = option.foundBy ?: style
                assertTrue("${entry.name} ${style.label}", option.curveWeight >= searchedBy.curveWeight)
                assertTrue("${entry.name} ${style.label} moved down", searchedBy <= style)
                if (option.budget == DistinctRideStyles.Budget.STYLE) {
                    assertTrue(
                        "${entry.name} ${style.label} within budget",
                        AlternativePolicy.withinGlobalDetourBudget(option.path, optimum, style),
                    )
                }
                if (entry.name != "Skyline Circuit to Palmwoods") {
                    assertEquals("${entry.name} ${style.label} budget", DistinctRideStyles.Budget.STYLE, option.budget)
                }
            }
            // All 21 stop pairs must be meaningfully different: a continuous
            // unshared stretch of clamp(5% of the ride, 750 m, 3 km) in at
            // least one direction, and under 99% of the longer ride's roads.
            RideStyle.entries.forEachIndexed { index, higher ->
                RideStyle.entries.take(index).forEach { lower ->
                    assertFalse(
                        "${entry.name} ${higher.label} repeats ${lower.label}",
                        DistinctRideStyles.sameRide(shown.getValue(higher), shown.getValue(lower)),
                    )
                }
            }
        }
    }

    /**
     * Round trips on the real graph through the production router: every
     * stop is a loop of its own that ends where it starts, Fastest lands
     * within the wished length, Max twisty is curvier than Fastest, and a
     * rebuild through the loop's own corners (what guidance does after an
     * off-route miss) rides the same loop.
     */
    @Test(timeout = 1_200_000)
    fun roundTripsAreSevenDifferentLoopsBackToTheStart() {
        val router = GraphHopperRouter(context)
        ROUND_TRIP_STARTS.forEach { (name, start) ->
            listOf(RoundTripLength.Time(60), RoundTripLength.Distance(80), RoundTripLength.Distance(50)).forEach { length ->
                val options = runBlocking {
                    RoundTrips.resolve(start, length, loop = { style, shape ->
                        try {
                            router.route(
                                start, start, style.level.toDouble(),
                                viaPoints = shape.vias, passThrough = true, viaSearchMetres = shape.searchMeters,
                            ).routes.single()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    })
                }
                println(
                    "ROUND_TRIP_DEVICE $name ${length.label} " + RideStyle.entries.joinToString(" ") { style ->
                        val path = options.getValue(style).path
                        "${style.label}=${(path.distance / 1000).toInt()}km/${path.time / 60_000}min/" +
                            "${(curvyDistanceFraction(path.points) * 100).toInt()}%"
                    },
                )
                val shown = options.mapValues { AlternativePolicy.cachedRoute(it.value.path) }
                options.forEach { (style, option) ->
                    val label = "$name ${length.label} ${style.label}"
                    assertNull("$label repeats a stop", option.repeats)
                    assertFalse("$label turns around", DistinctRideStyles.turnsAround(option.path))
                    val points = option.path.points
                    val gap = com.graphhopper.util.DistanceCalcEarth.DIST_EARTH.calcDist(
                        points.getLat(0), points.getLon(0), points.getLat(points.size() - 1), points.getLon(points.size() - 1),
                    )
                    assertTrue("$label ends ${gap.toInt()} m from its start", gap < 1.0)
                    val lasso = RoundTrips.retrace(shown.getValue(style)).second
                    assertTrue("$label rides ${lasso.toInt()} m of road again mid-loop",
                        option.budget != DistinctRideStyles.Budget.STYLE || lasso <= RoundTrips.lassoAllowance(option.path.distance))
                    val corners = RoundTrips.viaPointsOf(option.path)
                    assertEquals("$label corners", 2, corners.size)
                    val rebuilt = router.route(start, start, style.level.toDouble(), viaPoints = corners, passThrough = true)
                        .routes.single()
                    assertTrue("$label rebuilds onto another loop",
                        DistinctRideStyles.sameRide(AlternativePolicy.cachedRoute(rebuilt), shown.getValue(style)))
                }
                assertEquals("$name ${length.label} Fastest budget", DistinctRideStyles.Budget.STYLE,
                    options.getValue(RideStyle.FASTEST).budget)
                assertTrue("$name ${length.label} Max twisty is curvier than Fastest",
                    curvyDistanceFraction(options.getValue(RideStyle.MAX_TWISTY).path.points) >
                        curvyDistanceFraction(options.getValue(RideStyle.FASTEST).path.points))
                RideStyle.entries.forEachIndexed { index, higher ->
                    RideStyle.entries.take(index).forEach { lower ->
                        assertFalse("$name ${length.label} ${higher.label} repeats ${lower.label}",
                            DistinctRideStyles.sameRide(shown.getValue(higher), shown.getValue(lower)))
                    }
                }
            }
        }
    }

    /**
     * The old dial remembered which detents had been visited, so the same
     * knob position produced different rides depending on the path taken. A
     * style's carousel must now depend only on its inputs.
     */
    @Test(timeout = 600_000)
    fun styleResultsDoNotDependOnVisitingOrder() {
        val entry = ROUTE_CORPUS.first { it.alternativesExpected }
        val direct = GraphHopperRouter(context).route(entry.from, entry.to, RideStyle.TWISTY.level.toDouble())
        val stepped = GraphHopperRouter(context).let { router ->
            RideStyle.entries.forEach { router.route(entry.from, entry.to, it.level.toDouble()) }
            router.route(entry.from, entry.to, RideStyle.TWISTY.level.toDouble())
        }
        assertEquals(direct.routes.size, stepped.routes.size)
        direct.routes.zip(stepped.routes).forEach { (left, right) ->
            assertEquals(left.distance, right.distance, 0.0)
            assertEquals(left.time, right.time)
        }
    }

    /**
     * Curvier stops must buy curvier roads on a corridor with a real choice,
     * and Avoid motorways must cut motorway/trunk distance without breaking
     * the ride.
     */
    /**
     * The squared straightness penalty and the curvy stops' wider alternative
     * budget exist for this corridor: before them, Max twisty from Palmwoods
     * returned the 12% curvy coastal ride and rejected the D'Aguilar loop
     * (~26% curvy) as a detour. Desktop calibration: `CurvyCorridorProbe`.
     */
    @Test(timeout = 600_000)
    fun maxTwistyOffersTheRangeLoopFromPalmwoods() {
        val router = GraphHopperRouter(context)
        val entry = ROUTE_CORPUS.single { it.name == "Palmwoods to Brisbane CBD range loop" }
        val fastest = router.route(entry.from, entry.to, RideStyle.FASTEST.level.toDouble()).routes
        val maxTwisty = router.route(entry.from, entry.to, RideStyle.MAX_TWISTY.level.toDouble(), alternatives = true).routes
        val fastestCurvy = curvyDistanceFraction(fastest.first().points)
        val cards = maxTwisty.map { curvyDistanceFraction(it.points) }
        println("RANGE_LOOP_PROBE fastest=%.3f maxTwisty=%s".format(fastestCurvy, cards.map { "%.3f".format(it) }))
        assertTrue("Max twisty primary must be clearly curvier than Fastest: $fastestCurvy vs $cards", cards.first() >= fastestCurvy + 0.08)
        assertTrue("Max twisty must offer a range-road card at least 20% curvy: $cards", cards.any { it >= 0.20 })
        maxTwisty.drop(1).forEach { card ->
            assertTrue(card.time <= maxTwisty.first().time * RideStyle.MAX_TWISTY.maxAlternativeTimeRatio)
        }
    }

    @Test(timeout = 900_000)
    fun stylesAndAvoidMotorwaysChangeTheRideMeasurably() {
        val router = GraphHopperRouter(context)
        val entry = ROUTE_CORPUS.first { it.alternativesExpected }
        val curvy = RideStyle.entries.map { style ->
            val primary = router.route(entry.from, entry.to, style.level.toDouble()).routes.first()
            curvyDistanceFraction(primary.points)
        }
        println("STYLE_PROBE ${entry.name}: curvy=${curvy.map { "%.3f".format(it) }}")
        assertTrue(
            "Max twisty must ride a curvier primary than Fastest: $curvy",
            curvy.last() > curvy.first(),
        )

        // Only real corridors: the corpus also holds deliberate failure cases.
        val corridor = ROUTE_CORPUS.filter { it.goldDistanceMeters != null }
            .maxBy { motorwayMeters(router.route(it.from, it.to).routes.first()) }
        val plain = router.route(corridor.from, corridor.to).routes.first()
        val avoiding = router.route(corridor.from, corridor.to, avoidMotorways = true).routes.first()
        println(
            "MOTORWAY_PROBE ${corridor.name}: plain=${motorwayMeters(plain)}m " +
                "avoiding=${motorwayMeters(avoiding)}m",
        )
        assertTrue("corpus has no motorway corridor to test", motorwayMeters(plain) > 1_000.0)
        assertTrue(motorwayMeters(avoiding) < motorwayMeters(plain))
    }

    /**
     * Route settings ride factors on the bundled graph (calibrated with the
     * desktop RideFactorsProbe): the graph carries elevation, stops and
     * urban density; Avoid stops and towns trades the Fastest Brisbane to
     * Samford ride (~90 stops) for a different one with a few dozen; Hills
     * and elevation sends Max twisty Brisbane to Toowoomba over clearly more
     * climbing and leaves Fastest untouched.
     */
    @Test(timeout = 900_000)
    fun rideFactorsMeasurablyChangeTheRideOnTheRealGraph() {
        val router = GraphHopperRouter(context)
        val brisbane = com.graphhopper.util.shapes.GHPoint(-27.4698, 153.0251)
        val samford = com.graphhopper.util.shapes.GHPoint(-27.3353, 152.7720)
        val toowoomba = com.graphhopper.util.shapes.GHPoint(-27.5598, 151.9507)

        val plain = router.route(brisbane, samford).routes.first()
        assertEquals(GraphFeatures.ALL, router.features)
        val avoiding = router.route(brisbane, samford, factors = RideFactors(stopsAndTowns = true)).routes.first()
        val stops = routeStopCount(avoiding)
        val shared = sharedEdgeFraction(plain, avoiding)
        println(
            "FACTOR_PROBE stops Brisbane-Samford: plain=${plain.time / 60_000} min, " +
                "avoiding=${avoiding.time / 60_000} min, stops=$stops, shared=${"%.2f".format(shared)}",
        )
        assertTrue("the avoiding ride must report its stops", stops != null)
        assertTrue("Avoid stops and towns must leave the signalled corridor: $stops stops", stops!! <= 40)
        assertTrue("the ride must actually change: shared $shared", shared < 0.5)
        assertTrue("stops cost time, but never strand the ride", avoiding.time < plain.time * 1.4)
        assertNull(routeStopCount(plain))

        val maxTwisty = RideStyle.MAX_TWISTY.level.toDouble()
        val flatter = router.route(brisbane, toowoomba, maxTwisty).routes.first()
        val hillier = router.route(brisbane, toowoomba, maxTwisty, factors = RideFactors(elevation = true)).routes.first()
        println(
            "FACTOR_PROBE hills Brisbane-Toowoomba Max twisty: plain climb=${routeClimbMeters(flatter.points)} m, " +
                "hills climb=${routeClimbMeters(hillier.points)} m",
        )
        assertTrue(flatter.points.is3D)
        assertTrue(
            "Hills and elevation must climb clearly more",
            routeClimbMeters(hillier.points) > routeClimbMeters(flatter.points) + 150.0,
        )
        val fastest = router.route(brisbane, toowoomba).routes.first()
        val fastestWithHills = router.route(brisbane, toowoomba, factors = RideFactors(elevation = true)).routes.first()
        assertEquals("Fastest reads no geometry, hills change nothing", fastest.distance, fastestWithHills.distance, 0.0)
        router.close()
    }

    private fun sharedEdgeFraction(reference: com.graphhopper.ResponsePath, other: com.graphhopper.ResponsePath): Double {
        fun edges(path: com.graphhopper.ResponsePath) =
            path.pathDetails["edge_id"].orEmpty().map { (it.value as Number).toInt() }
        val referenceEdges = edges(reference).toSet()
        val otherEdges = edges(other)
        if (otherEdges.isEmpty()) return 1.0
        return otherEdges.count { it in referenceEdges }.toDouble() / otherEdges.size
    }

    private fun motorwayMeters(path: com.graphhopper.ResponsePath): Double {
        val points = path.points
        return path.pathDetails["road_class"].orEmpty()
            .filter { it.value.toString().let { value -> value == "motorway" || value == "trunk" } }
            .sumOf { detail ->
                (detail.first until detail.last).sumOf { index ->
                    com.graphhopper.util.DistanceCalcEarth.DIST_EARTH.calcDist(
                        points.getLat(index), points.getLon(index),
                        points.getLat(index + 1), points.getLon(index + 1),
                    )
                }
            }
    }

    @Test(timeout = 300_000)
    fun detentResultsAreCachedAndDeterministic() {
        val entry = ROUTE_CORPUS.first()
        val router = GraphHopperRouter(context)
        val first = router.route(entry.from, entry.to, 0.0)
        val second = router.route(entry.from, entry.to, 0.0)
        assertEquals(first.routes.size, second.routes.size)
        first.routes.zip(second.routes).forEach { (left, right) ->
            assertEquals(left.distance, right.distance, 0.0)
            assertEquals(left.time, right.time)
        }
    }

    @Test(timeout = 300_000)
    fun savedGeometryMatchingSelectsClosestCandidate() {
        val entry = ROUTE_CORPUS.first()
        val routes = GraphHopperRouter(context)
            .route(entry.from, entry.to, RideStyle.TWISTY.level.toDouble(), alternatives = true).routes
        if (routes.size > 1) {
            val expected = 1
            val stored = routes[expected].points.toGeoPoints()
            val candidates = routes.map { it.points.toGeoPoints() }
            assertEquals(expected, RouteSimilarity.bestMatchIndex(stored, candidates))
        }
    }

    @Test(timeout = 300_000)
    fun importedNambourLoopRoutesThroughEveryStop() {
        // Stops from the supplied Furkot Nambour GPX. The old importer reduced
        // this closed ride to one endpoint request and lost the loop.
        val stops = listOf(
            com.graphhopper.util.shapes.GHPoint(-26.625491, 152.958018), // Nambour
            com.graphhopper.util.shapes.GHPoint(-26.954705, 152.777724), // Woodford
            com.graphhopper.util.shapes.GHPoint(-27.195919, 152.824545), // Dayboro
            com.graphhopper.util.shapes.GHPoint(-27.039270, 152.860723), // Wamuran
            com.graphhopper.util.shapes.GHPoint(-26.625618, 152.958156), // return to Nambour
        )
        val path = GraphHopperRouter(context).route(
            from = stops.first(),
            to = stops.last(),
            viaPoints = stops.drop(1).dropLast(1),
        ).routes.single()
        val routed = path.points.toGeoPoints()

        assertTrue("imported loop should remain a full ride", path.distance > 100_000.0)
        stops.forEach { stop ->
            val nearest = routed.minOf { RouteSimilarity.haversineMeters(it, stop.toGeoPoint()) }
            assertTrue("route missed imported stop $stop by ${nearest}m", nearest < 1_000.0)
        }
    }

    /**
     * An imported GPX track is ridden road for road on the shipped graph: the
     * committed fixture is a recorded Samford → Mount Glorious → Mount Nebo
     * loop with GPS noise, a U-turn at the café stop and a waypoint 7 km off
     * the ride. The old import (a shaping point every 1.5 km at the rider's
     * style) left kilometres of it unridden; the followed track rides it
     * within 1%, with the off-ride waypoint left out and the U-turn kept.
     */
    @Test(timeout = 300_000)
    fun importedGpxTrackIsFollowedRoadForRoad() {
        val gpx = InstrumentationRegistry.getInstrumentation().context.assets
            .open("gpx/mount-glorious-loop.gpx").use { com.curvemaps.app.storage.GpxParser.parse(it) }
        val plan = com.curvemaps.app.storage.GpxTrackFollow.plan(gpx)
        assertEquals(com.curvemaps.app.storage.GpxRideMode.FOLLOW_TRACK, plan.mode)
        assertEquals(1, plan.ignoredWaypoints)
        val router = GraphHopperRouter(context)
        val params = com.curvemaps.app.RouteParams(
            from = com.graphhopper.util.shapes.GHPoint(plan.line.first().lat, plan.line.first().lon),
            to = com.graphhopper.util.shapes.GHPoint(plan.line.last().lat, plan.line.last().lon),
            complexity = RideStyle.MAX_TWISTY.level.toDouble(),
            blockUnpaved = false,
            followTrack = plan,
        )
        val path = com.curvemaps.app.followGpxTrack(router, params, plan) {}.routes.single()
        val measure = com.curvemaps.app.storage.GpxTrackFollow.measure(plan.line, path.points.toGeoPoints())
        val length = com.curvemaps.app.storage.GpxGeometry.lengthMeters(plan.line)
        println("GPX_FOLLOW device: ${measure.offTrackMeters} m not ridden, ${measure.extraMeters} m off, of $length m")
        assertTrue("followed track misses ${measure.offTrackMeters} m", measure.offTrackMeters <= 0.01 * length)
        assertTrue("followed track adds ${measure.extraMeters} m off it", measure.extraMeters <= 0.01 * length)
        // A rebuild from the shown path's own shaping points rides it again.
        val vias = RoundTrips.viaPointsOf(path)
        val headings = com.curvemaps.app.storage.GpxTrackFollow.headingsAlong(
            path.points.toGeoPoints(),
            vias.map { it.toGeoPoint() },
        )
        val rebuilt = router.route(params.from, params.to, viaPoints = vias, viaHeadings = headings).routes.single()
        val again = com.curvemaps.app.storage.GpxTrackFollow.measure(plan.line, rebuilt.points.toGeoPoints())
        assertTrue("rebuild misses ${again.offTrackMeters} m", again.offTrackMeters <= 0.01 * length)
        assertTrue("rebuild adds ${again.extraMeters} m", again.extraMeters <= 0.01 * length)
    }

    /**
     * Lane guidance end to end on the shipped graph: the production router
     * requests the `moto_lanes` detail, the lane-aware import stored it, and
     * the track factory turns it into recommendations. Brisbane CBD to
     * Annerley crosses several mapped multi-lane approaches (Merivale Street
     * carries turn:lanes arrows), so a graph imported with the stock
     * GraphHopper command, or a router that drops the path detail, fails here.
     */
    @Test(timeout = 300_000)
    fun cbdRouteCarriesLaneGuidanceFromTheGraph() {
        val path = GraphHopperRouter(context).route(
            com.graphhopper.util.shapes.GHPoint(-27.4698, 153.0251),
            com.graphhopper.util.shapes.GHPoint(-27.5598, 153.0811),
        ).routes.first()
        assertTrue(
            "route must carry the moto_lanes path detail",
            path.pathDetails[MOTO_LANES_DETAIL].orEmpty().any { it.value is String },
        )
        val turns = com.curvemaps.app.routing.navigation.RouteTrackFactory.fromPath(path).turns
        val laned = turns.mapNotNull { it.lanes }
        assertTrue("expected several multi-lane turn approaches, got ${laned.size}", laned.size >= 3)
        assertTrue("expected at least one turn:lanes-marked approach", laned.any { it.marked })
        laned.forEach { guidance ->
            assertTrue(guidance.lanes.size >= 2)
            assertTrue(guidance.leftHandTraffic)
            assertTrue(guidance.recommendedIndices.isNotEmpty())
        }
    }

    /**
     * Gold-baseline drift alert. Wide corpus bands only catch catastrophic
     * rerouting; a quiet road-preference change that adds +40% distance stays
     * green forever. These alert at ±15% against values measured on device
     * and committed above. The GOLD_PROBE lines make recalibration trivial:
     * run once, replace the golds with the printed numbers.
     */
    @Test(timeout = 900_000)
    fun fastestRoutesMatchCommittedGoldBaselines() {
        val router = GraphHopperRouter(context)
        val missing = mutableListOf<String>()
        // Every committed gold must be exercised. Entries without golds (the
        // deliberate degenerate cases in ROUTE_CORPUS) are skipped, so future
        // corpus additions stay covered without touching this loop.
        ROUTE_CORPUS.filter { it.goldDistanceMeters != null || it.goldDurationMillis != null }.forEach { entry ->
            val result = router.route(entry.from, entry.to, 0.0)
            val path = result.routes.first()
            println(
                "GOLD_PROBE ${entry.name}: distance_meters=${path.distance}, " +
                    "duration_ms=${path.time}, routes=${result.routes.size}",
            )
            val goldDistance = entry.goldDistanceMeters
            val goldDuration = entry.goldDurationMillis
            if (goldDistance == null || goldDuration == null) {
                missing += entry.name
                return@forEach
            }
            assertWithinAlertBand(entry.name, "distance", path.distance, goldDistance)
            assertWithinAlertBand(entry.name, "duration", path.time.toDouble(), goldDuration.toDouble())
        }
        assertTrue(
            "corpus entries still lack committed gold baselines: $missing. " +
                "Run this suite once on an emulator and fill RouteCorpusEntry golds from the GOLD_PROBE lines.",
            missing.isEmpty(),
        )
    }

    private fun assertWithinAlertBand(entry: String, label: String, actual: Double, gold: Double) {
        val relative = abs(actual - gold) / gold
        assertTrue(
            "$entry $label drifted from its gold baseline: actual=$actual gold=$gold " +
                "(%.1f%% > 15%%). If OSM data or weighting changed deliberately, ".format(relative * 100.0) +
                "re-run calibrationProbe and update ROUTE_CORPUS golds.",
            relative <= 0.15,
        )
    }

    @Ignore("Re-run after graph rebuilds to recalibrate corpus baselines")
    @Test(timeout = 300_000)
    fun calibrationProbe() {
        val router = GraphHopperRouter(context)
        ROUTE_CORPUS.take(10).forEach { entry ->
            val fastest = router.route(entry.from, entry.to, 0.0)
            val alternatives = router.route(entry.from, entry.to, RideStyle.TWISTY.level.toDouble(), alternatives = true)
            val styles = RideStyle.entries.joinToString { style ->
                val primary = router.route(entry.from, entry.to, style.level.toDouble()).routes.first()
                "${style.label}=${(primary.distance / 1000).toInt()}km/" +
                    "${(curvyDistanceFraction(primary.points) * 100).toInt()}%curvy"
            }
            println(
                "GOLD_PROBE ${entry.name}: distance_meters=${fastest.routes.first().distance}, " +
                    "duration_ms=${fastest.routes.first().time}, routes=${fastest.routes.size}, " +
                    "alternatives=${alternatives.routes.size >= 2}, styles=[$styles]",
            )
        }
    }

    private fun assertRoundTrip(points: List<GeoPoint>) {
        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
        assertTrue(decoded.size >= 2)
        points.zip(decoded).forEach { (original, restored) ->
            assertEquals(original.lat, restored.lat, 5.1e-6)
            assertEquals(original.lon, restored.lon, 5.1e-6)
        }
    }

    private fun com.graphhopper.util.shapes.GHPoint.toGeoPoint() = GeoPoint(lat, lon)

    private fun com.graphhopper.util.PointList.toGeoPoints(): List<GeoPoint> =
        List(size()) { index -> GeoPoint(getLat(index), getLon(index)) }
}
