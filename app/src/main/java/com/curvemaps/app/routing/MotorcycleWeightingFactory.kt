package com.curvemaps.app.routing

import com.graphhopper.config.Profile
import com.graphhopper.routing.DefaultWeightingFactory
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.EncodedValueLookup
import com.graphhopper.routing.ev.RoadAccess
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadClassLink
import com.graphhopper.routing.ev.Roundabout
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.ev.TurnRestriction
import com.graphhopper.routing.ev.UrbanDensity
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.weighting.DefaultTurnCostProvider
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.routing.weighting.custom.CustomWeighting
import com.graphhopper.routing.weighting.custom.CustomWeighting.MaxCalc
import com.graphhopper.routing.weighting.custom.MotorcycleWeightingHelper
import com.graphhopper.storage.BaseGraph
import com.graphhopper.util.CustomModel
import com.graphhopper.util.PMap
import com.graphhopper.util.Parameters
import com.graphhopper.util.TurnCostsConfig

/**
 * WeightingFactory that sidesteps Janino for the motorcycle custom weighting.
 *
 * The default [DefaultWeightingFactory] builds custom weightings through
 * CustomModelParser, which compiles the custom model expressions with Janino
 * into JVM bytecode at load time. ART cannot load that bytecode, so routing
 * fails on Android. This factory mirrors DefaultWeightingFactory's custom
 * branch for the `motorcycle` profile only, assembling
 * [CustomWeighting.Parameters] from the pre-compiled [MotorcycleWeightingHelper]
 * instead.
 *
 * The factory also implements the ride-style dial (request hint
 * [MOTO_RIDE_STYLE], a [RideStyle.level]), the Route settings road toggles and
 * the ride factors ([MOTO_HILLS], [MOTO_STOPS_AND_TOWNS]) through
 * [ComplexityWeighting]. Graph loading has no hint and returns pure
 * [CustomWeighting], preserving the stored profile and its CH preparation.
 * User routes always carry the hint and require flexible routing.
 *
 * Any other profile is delegated to the default factory: non-custom weightings
 * are safe (they do not involve Janino), while custom weightings on other
 * profiles are rejected with a clear error instead of silently applying the
 * motorcycle model — this build only ships the pre-compiled motorcycle helper.
 */
class MotorcycleWeightingFactory(
    private val graph: BaseGraph,
    private val encodingManager: EncodingManager,
    /**
     * The loaded graph's junction loops, shared by every request
     * ([loadMotoGraphHopper]); GraphHopper builds a new factory per route.
     */
    sharedSmallLoops: SmallLoopEdges? = null,
    /**
     * The loaded graph's per-edge curve factors and stop counts, shared by
     * every request like [sharedSmallLoops]; null keeps them per request.
     */
    private val edgeFactors: EdgeFactors? = null,
) : WeightingFactory {

    private val delegate by lazy { DefaultWeightingFactory(graph, encodingManager) }

    private val smallLoops by lazy {
        sharedSmallLoops ?: SmallLoopEdges(graph, encodingManager.getBooleanEncodedValue("car_access"))
    }

    override fun createWeighting(profile: Profile, hints: PMap, disableTurnCosts: Boolean): Weighting {
        // The pre-compiled helper implements exactly one model: the motorcycle
        // profile's. Never apply it to any other profile.
        if (profile.name != MOTORCYCLE_PROFILE) {
            if (CustomWeighting.NAME.equals(profile.weighting, ignoreCase = true))
                throw IllegalArgumentException(
                    "No pre-compiled weighting helper for profile '${profile.name}': " +
                        "this build only ships the motorcycle custom model (ART cannot load " +
                        "Janino-compiled custom weightings)"
                )
            return delegate.createWeighting(profile, hints, disableTurnCosts)
        }
        if (!CustomWeighting.NAME.equals(profile.weighting, ignoreCase = true))
            return delegate.createWeighting(profile, hints, disableTurnCosts)

        // Mirrors DefaultWeightingFactory.createWeighting's custom branch.
        val queryCustomModel = hints.getObject(CustomModel.KEY, null as CustomModel?)
        if (queryCustomModel != null)
            throw IllegalArgumentException(
                "Request-level custom model overrides are not supported on Android: " +
                    "the pre-compiled weighting helper only implements the motorcycle profile model"
            )
        val turnCostsConfig = profile.turnCostsConfig
        val mergedCustomModel = CustomModel.merge(profile.customModel, queryCustomModel)
        if (hints.has(Parameters.Routing.HEADING_PENALTY))
            mergedCustomModel.headingPenalty = hints.getDouble(
                Parameters.Routing.HEADING_PENALTY,
                Parameters.Routing.DEFAULT_HEADING_PENALTY,
            )

        // Resolved once here because the model's maximum effective speed is
        // used by both CustomWeighting's and FastestWeighting's admissible A*
        // lower bounds.
        val avgSpeedEnc = encodingManager.getDecimalEncodedValue("car_average_speed")

        val preparedModel = prepareModel(mergedCustomModel, encodingManager, avgSpeedEnc)

        val turnCostProvider = if (profile.hasTurnCosts() && !disableTurnCosts) {
            val turnRestrictionEnc =
                encodingManager.getTurnBooleanEncodedValue(TurnRestriction.key(profile.name))
                    ?: throw IllegalArgumentException(
                        "Cannot find turn restriction encoded value for ${profile.name}"
                    )
            val uTurnCosts = hints.getInt(
                Parameters.Routing.U_TURN_COSTS,
                turnCostsConfig!!.getUTurnCosts(),
            )
            val tcConfig = TurnCostsConfig(turnCostsConfig).setUTurnCosts(uTurnCosts)
            DefaultTurnCostProvider(
                turnRestrictionEnc,
                graph,
                tcConfig,
                preparedModel.parameters.turnPenaltyMapping,
            )
        } else {
            if (mergedCustomModel.turnPenalty.isNotEmpty())
                throw IllegalArgumentException(
                    "The turn_penalty feature is not supported for ${profile.name}. " +
                        "You have to enable this in 'turn_costs' in config.yml."
                )
            TurnCostProvider.NO_TURN_COST_PROVIDER
        }

        // Graph load has no dial hint and must receive the exact pure custom
        // weighting that was used to import CH. GraphHopperRouter includes the
        // hint on every user route, including Fastest.
        if (!hints.has(MOTO_RIDE_STYLE))
            return CustomWeighting(turnCostProvider, preparedModel.parameters)
        val style = RideStyle.fromLevel(hints.getDouble(MOTO_RIDE_STYLE, 0.0))

        val carAccessEnc: BooleanEncodedValue = encodingManager.getBooleanEncodedValue("car_access")
        val surfaceEnc = encodingManager.getEnumEncodedValue(Surface.KEY, Surface::class.java)
        val roadClassEnc = encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java)
        val roadAccessEnc = encodingManager.getEnumEncodedValue(RoadAccess.KEY, RoadAccess::class.java)
        val siblingEdgeIds = hints.getObject(MOTO_SIBLING_EDGES, emptySet<Int>())
        val siblingEdgePenalty = hints.getDouble(MOTO_SIBLING_EDGE_PENALTY, 0.0).coerceAtLeast(0.0)
        val helper = preparedModel.helper
        return ComplexityWeighting(
            FastestWeighting(
                helper::getSpeed,
                preparedModel.maxSpeedKmh,
                turnCostProvider,
                carAccessEnc,
            ),
            { edge, reverse -> helper.getPriority(edge, reverse) },
            style,
            carAccessEnc,
            surfaceEnc,
            roadClassEnc,
            roadAccessEnc,
            hints.getBool(BLOCK_UNPAVED, false),
            hints.getBool(AVOID_MOTORWAYS, false),
            siblingEdgeIds,
            siblingEdgePenalty,
            hints.getDouble(MOTO_CURVE_WEIGHT, style.curveWeight).coerceIn(0.0, RideStyle.MAX_CURVE_WEIGHT),
            hills = hints.getBool(MOTO_HILLS, false),
            stopsAndTowns = hints.getBool(MOTO_STOPS_AND_TOWNS, false),
            // Graphs imported before the ride factors have no urban density;
            // towns then add nothing instead of failing the route.
            urbanDensityEnc = if (encodingManager.hasEncodedValue(UrbanDensity.KEY))
                encodingManager.getEnumEncodedValue(UrbanDensity.KEY, UrbanDensity::class.java)
            else null,
            baseGraph = graph,
            roadClassLinkEnc = if (encodingManager.hasEncodedValue(RoadClassLink.KEY))
                encodingManager.getBooleanEncodedValue(RoadClassLink.KEY)
            else null,
            roundaboutEnc = if (encodingManager.hasEncodedValue(Roundabout.KEY))
                encodingManager.getBooleanEncodedValue(Roundabout.KEY)
            else null,
            smallLoops = smallLoops,
            edgeFactors = edgeFactors,
        )
    }

    private data class PreparedModel(
        val parameters: CustomWeighting.Parameters,
        val helper: MotorcycleWeightingHelper,
        val maxSpeedKmh: Double,
    )

    private fun prepareModel(
        customModel: CustomModel,
        lookup: EncodedValueLookup,
        avgSpeedEnc: DecimalEncodedValue,
    ): PreparedModel {
        val prio = MotorcycleWeightingHelper()
        prio.init(customModel, lookup, CustomModel.getAreasAsMap(customModel.areas))

        // GraphHopper's CustomWeightingHelper.calcMaxSpeed()/calcMaxPriority()
        // are final and range-check the value expressions ("0.9 *
        // car_average_speed", ...) through ValueExpressionVisitor, which
        // compiles them with Janino — another ART-incompatible path. Only the
        // MAX values are consumed at runtime
        // (CustomWeighting.calcMinWeightPerDistance), so compute them directly,
        // mirroring FindMinMax for the fixed motorcycle model
        // (tools/gh/motorcycle.json — the canonical model):
        //   maxPriority: every priority statement is MULTIPLY with value <= 1
        //     and has no ELSE, so the neutral branch keeps max = 1.
        //   maxSpeed: groups [IF true LIMIT 0.9*car_average_speed],
        //     [IF true LIMIT 120], [IF rough-surface LIMIT 30] ->
        //     min(120, 0.9 * max(car_average_speed)).
        // If the model statements change, regenerate MotorcycleWeightingHelper
        // AND update these computations (see docs/agents/graph-pipeline.md).
        val maxSpeed = minOf(120.0, 0.9 * avgSpeedEnc.maxOrMaxStorableDecimal)

        val parameters = CustomWeighting.Parameters(
            prio::getSpeed,
            MaxCalc { maxSpeed },
            prio::getPriority,
            MaxCalc { 1.0 },
            prio::getTurnPenalty,
            customModel.distanceInfluence ?: 0.0,
            customModel.headingPenalty ?: Parameters.Routing.DEFAULT_HEADING_PENALTY,
        )
        return PreparedModel(parameters, prio, maxSpeed)
    }
}
