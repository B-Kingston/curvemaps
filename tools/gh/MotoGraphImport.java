import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.application.GraphHopperServerConfiguration;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.Country;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.ImportUnit;
import com.graphhopper.routing.util.OSMParsers;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.search.KVStorage;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.PMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The project's graph import: GraphHopper 11's own {@code import} command plus two
 * way-tag parsers that store per-direction lane guidance ({@value LaneTags#KEY})
 * and stop controls ({@value StopTags#KEY}) in edge KV storage.
 *
 * Run from the repo root with JDK 17 (single-file source launch, no compile step):
 * <pre>
 *   java -Xmx12g -cp tools/gh/graphhopper-web-11.0.jar tools/gh/MotoGraphImport.java import tools/gh/config.yml
 *   java -cp tools/gh/graphhopper-web-11.0.jar tools/gh/MotoGraphImport.java --self-test
 * </pre>
 *
 * Why KV storage and not an encoded value: edge flags are already 57 of 64
 * bits, so lane arrows as encoded values would grow every edge of the graph,
 * while KV entries cost bytes only on the few edges that carry lane tags. The
 * profile, encoded values, and custom model are untouched, so the stored
 * profile hash (motorcycle|198752012) and the app's pre-compiled weighting stay
 * valid. OSMReader.addEdge runs the way-tag parsers before it writes the way's
 * {@code key_values} map to the edge, which is what lets a parser add entries.
 *
 * Wire format of {@value LaneTags#KEY} (one value per travel direction, read by
 * the app's {@code LaneGuidance.parse}):
 * <pre>  &lt;L|R&gt;:&lt;lane&gt;|&lt;lane&gt;|...</pre>
 * L/R is the driving side of the way's country (L = left-hand traffic). Lanes
 * are listed left to right in the direction of travel, as OSM orders them.
 * Each lane token is {@code ?} when only the lane count is known, empty for a
 * marked lane without a turn arrow (none/merge), or a subset of {@code lsru}
 * (left, straight, right, u-turn) in that order.
 *
 * Wire format of {@value StopTags#KEY} (one value per travel direction, read by
 * the app's {@code EdgeRideFeatures}): the number of HALF stops a rider meets on
 * the edge in that direction, as a decimal string; absent when zero. See
 * {@link StopTags} for what counts as a stop and why end nodes count half.
 */
public class MotoGraphImport {

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            LaneTags.selfTest();
            StopTags.selfTest();
            System.out.println("MotoGraphImport self-test passed");
            return;
        }
        if (args.length != 2 || !args[0].equals("import")) {
            System.err.println("usage: MotoGraphImport import <config.yml> | --self-test");
            System.exit(2);
        }
        // Same mapping the Dropwizard `import` command applies to config.yml.
        ObjectMapper yaml = io.dropwizard.jackson.Jackson.newObjectMapper(new YAMLFactory());
        GraphHopperConfig config = yaml
            .readValue(new File(args[1]), GraphHopperServerConfiguration.class)
            .getGraphHopperConfiguration();
        // GraphHopper silently LOADS an existing graph instead of importing, which
        // would ship a graph without lane guidance while looking like a rebuild.
        File existing = new File(config.getString("graph.location", "graph-cache"), "properties");
        if (existing.exists()) {
            System.err.println("refusing to import over an existing graph: delete " + existing.getParent() + " first");
            System.exit(1);
        }
        GraphHopper hopper = new GraphHopper() {
            @Override
            protected OSMParsers buildOSMParsers(
                Map<String, PMap> encodedValuesWithProps,
                Map<String, ImportUnit> activeImportUnits,
                Map<String, List<String>> restrictionVehicleTypesByProfile,
                List<String> ignoredHighways
            ) {
                OSMParsers parsers = super.buildOSMParsers(
                    encodedValuesWithProps, activeImportUnits, restrictionVehicleTypesByProfile, ignoredHighways);
                parsers.addWayTagParser(new LaneTagParser());
                parsers.addWayTagParser(new StopTagParser());
                return parsers;
            }
        };
        hopper.init(config);
        hopper.importAndClose();
        System.out.println("lane guidance stored on " + LaneTagParser.taggedEdges + " edges");
        System.out.println("stop controls stored on " + StopTagParser.taggedEdges + " edges");
    }

    /**
     * Adds (or clears) the {@value StopTags#KEY} KV entry; never touches encoded values.
     *
     * OSMReader keeps ONE {@code key_values} map per way and reuses it for every
     * edge the way is split into, so this parser must also remove a value left by
     * the way's previous edge, or a signal would leak onto every later edge.
     */
    static final class StopTagParser implements TagParser {
        static long taggedEdges = 0;

        @Override
        public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
            List<Map<String, Object>> nodeTags = way.getTag("node_tags", Collections.<Map<String, Object>>emptyList());
            int[] halves = StopTags.count(nodeTags);
            @SuppressWarnings("unchecked")
            Map<String, KVStorage.KValue> existing =
                (Map<String, KVStorage.KValue>) way.getTag("key_values", Collections.<String, KVStorage.KValue>emptyMap());
            if (halves[0] == 0 && halves[1] == 0) {
                if (existing.containsKey(StopTags.KEY)) {
                    Map<String, KVStorage.KValue> cleared = new LinkedHashMap<>(existing);
                    cleared.remove(StopTags.KEY);
                    way.setTag("key_values", cleared);
                }
                return;
            }
            Map<String, KVStorage.KValue> merged = new LinkedHashMap<>(existing);
            merged.put(StopTags.KEY, halves[0] == halves[1]
                ? new KVStorage.KValue(Integer.toString(halves[0]))
                : new KVStorage.KValue(
                    halves[0] == 0 ? null : Integer.toString(halves[0]),
                    halves[1] == 0 ? null : Integer.toString(halves[1])));
            way.setTag("key_values", merged);
            taggedEdges++;
        }
    }

    /**
     * Pure OSM stop-control interpretation, kept free of GraphHopper types for the self-test.
     *
     * A stop is a node a rider must (or regularly has to) halt at: traffic
     * signals (road or pedestrian), stop signs, give-way signs and mini
     * roundabouts. Level crossings, plain zebra crossings and speed bumps are
     * not stops. {@code direction=forward|backward} (and the signal-specific
     * {@code traffic_signals:direction}) limits a control to one travel
     * direction, relative to the way; anything else applies to both.
     *
     * Interior nodes count one full stop (2 halves). A control on an end node
     * sits on the junction the edge shares with its neighbours, and a ride
     * passing through it uses two edges that both end there, so each end node
     * counts half: the ride meets exactly one stop. (A stop sign tagged on the
     * junction node itself, rather than just before it, also lands on the
     * through road; that is an OSM tagging limitation this does not guess around.)
     */
    static final class StopTags {
        static final String KEY = "moto_stops";

        /** {forward, backward} half-stop counts for one edge's node tags (one map per point). */
        static int[] count(List<Map<String, Object>> nodeTags) {
            int forward = 0, backward = 0;
            int last = nodeTags.size() - 1;
            for (int i = 0; i <= last; i++) {
                Map<String, Object> tags = nodeTags.get(i);
                if (tags == null || tags.isEmpty() || !isStop(tags)) continue;
                int weight = (i == 0 || i == last) ? 1 : 2;
                String direction = direction(tags);
                if (!direction.equals("backward")) forward += weight;
                if (!direction.equals("forward")) backward += weight;
            }
            return new int[]{forward, backward};
        }

        static boolean isStop(Map<String, Object> tags) {
            String highway = string(tags, "highway");
            switch (highway) {
                case "traffic_signals":
                case "stop":
                case "give_way":
                case "mini_roundabout":
                    return true;
                case "crossing":
                    return string(tags, "crossing").equals("traffic_signals");
                default:
                    return false;
            }
        }

        private static String direction(Map<String, Object> tags) {
            String own = string(tags, "traffic_signals:direction");
            String value = own.isEmpty() ? string(tags, "direction") : own;
            return value.equals("forward") || value.equals("backward") ? value : "both";
        }

        private static String string(Map<String, Object> tags, String key) {
            Object value = tags.get(key);
            return value instanceof String ? ((String) value).trim() : "";
        }

        static void selfTest() {
            Map<String, Object> none = Collections.emptyMap();
            Map<String, Object> signal = objectTags("highway", "traffic_signals");
            // Interior signal: one full stop each way.
            checkCounts(new int[]{2, 2}, count(Arrays.asList(none, signal, none)));
            // Junction signals count half on each edge that ends there.
            checkCounts(new int[]{1, 1}, count(Arrays.asList(signal, none, none)));
            checkCounts(new int[]{2, 2}, count(Arrays.asList(signal, none, signal)));
            // Directional controls apply to one travel direction only.
            checkCounts(new int[]{2, 0}, count(Arrays.asList(none, objectTags("highway", "stop", "direction", "forward"), none)));
            checkCounts(new int[]{0, 2}, count(Arrays.asList(none, objectTags("highway", "give_way", "direction", "backward"), none)));
            checkCounts(new int[]{0, 2}, count(Arrays.asList(none,
                objectTags("highway", "traffic_signals", "traffic_signals:direction", "backward", "direction", "forward"), none)));
            checkCounts(new int[]{2, 2}, count(Arrays.asList(none, objectTags("highway", "stop", "direction", "both"), none)));
            // Pedestrian lights stop traffic; zebra crossings, level crossings and bumps do not count.
            checkCounts(new int[]{2, 2}, count(Arrays.asList(none, objectTags("highway", "crossing", "crossing", "traffic_signals"), none)));
            checkCounts(new int[]{0, 0}, count(Arrays.asList(none, objectTags("highway", "crossing", "crossing", "zebra"), none)));
            checkCounts(new int[]{0, 0}, count(Arrays.asList(none, objectTags("railway", "level_crossing"), none)));
            checkCounts(new int[]{0, 0}, count(Arrays.asList(none, objectTags("traffic_calming", "bump"), none)));
            checkCounts(new int[]{2, 2}, count(Arrays.asList(none, objectTags("highway", "mini_roundabout"), none)));
            checkCounts(new int[]{0, 0}, count(Collections.<Map<String, Object>>emptyList()));
        }

        private static Map<String, Object> objectTags(String... kv) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
            return map;
        }

        private static void checkCounts(int[] expected, int[] actual) {
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError("expected " + Arrays.toString(expected) + " got " + Arrays.toString(actual));
            }
        }
    }

    /** Adds the {@value LaneTags#KEY} KV entry; never touches encoded values. */
    static final class LaneTagParser implements TagParser {
        static long taggedEdges = 0;

        @Override
        public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
            Object country = way.getTag("country", Country.MISSING);
            boolean rightHand = !(country instanceof Country) || ((Country) country).isRightHandTraffic();
            String[] values = LaneTags.encode(tagsOf(way), rightHand);
            if (values == null) return;
            @SuppressWarnings("unchecked")
            Map<String, KVStorage.KValue> existing =
                (Map<String, KVStorage.KValue>) way.getTag("key_values", Collections.<String, KVStorage.KValue>emptyMap());
            Map<String, KVStorage.KValue> merged = new LinkedHashMap<>(existing);
            merged.put(LaneTags.KEY, new KVStorage.KValue(values[0], values[1]));
            way.setTag("key_values", merged);
            taggedEdges++;
        }

        private static Map<String, String> tagsOf(ReaderWay way) {
            Map<String, String> tags = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : way.getTags().entrySet()) {
                if (e.getValue() instanceof String) tags.put(e.getKey(), (String) e.getValue());
            }
            return tags;
        }
    }

    /** Pure OSM lane-tag interpretation, kept free of GraphHopper types for the self-test. */
    static final class LaneTags {
        static final String KEY = "moto_lanes";
        static final int MAX_LANES = 10;

        /** Returns {forward, backward} values (either may be null), or null when neither is known. */
        static String[] encode(Map<String, String> tags, boolean rightHand) {
            String highway = tags.get("highway");
            if (highway == null) return null;
            int oneway = oneway(tags, highway);
            String side = rightHand ? "R" : "L";
            String fwd = oneway >= 0 ? direction(tags, "forward", oneway != 0, side) : null;
            String bwd = oneway <= 0 ? direction(tags, "backward", oneway != 0, side) : null;
            if (fwd == null && bwd == null) return null;
            return new String[]{fwd, bwd};
        }

        /** 1 = oneway along the way, -1 = oneway against it, 0 = two-way. */
        static int oneway(Map<String, String> tags, String highway) {
            String oneway = tags.getOrDefault("oneway", "");
            if (oneway.equals("-1") || oneway.equals("reverse")) return -1;
            if (oneway.equals("yes") || oneway.equals("1") || oneway.equals("true")) return 1;
            if (oneway.equals("no")) return 0;
            String junction = tags.getOrDefault("junction", "");
            if (junction.equals("roundabout") || junction.equals("circular")) return 1;
            if (highway.equals("motorway") || highway.equals("motorway_link")) return 1;
            return 0;
        }

        private static String direction(Map<String, String> tags, String dir, boolean oneway, String side) {
            String turn = tags.get("turn:lanes:" + dir);
            if (turn == null && oneway) turn = tags.get("turn:lanes");
            List<String> arrows = turn == null ? null : arrows(turn);
            Integer count = oneway ? firstInt(tags.get("lanes:" + dir), tags.get("lanes")) : twoWayCount(tags, dir);
            if (arrows != null && (count == null || count == arrows.size())) {
                return side + ":" + String.join("|", arrows);
            }
            // A turn:lanes value that disagrees with the lane count is not trusted.
            if (count == null || count < 1 || count > MAX_LANES) return null;
            return side + ":" + String.join("|", Collections.nCopies(count, "?"));
        }

        private static Integer twoWayCount(Map<String, String> tags, String dir) {
            Integer own = parse(tags.get("lanes:" + dir));
            if (own != null) return own;
            Integer total = parse(tags.get("lanes"));
            if (total == null) return null;
            String otherDir = dir.equals("forward") ? "backward" : "forward";
            Integer other = parse(tags.get("lanes:" + otherDir));
            int both = Objects.requireNonNullElse(parse(tags.get("lanes:both_ways")), 0);
            if (other != null) return total - other - both > 0 ? total - other - both : null;
            int split = total - both;
            // Without explicit per-direction tags only an even split is unambiguous.
            return split > 0 && split % 2 == 0 ? split / 2 : null;
        }

        /** Lane arrow tokens, or null when the value is malformed or too wide. */
        static List<String> arrows(String turnLanes) {
            String[] lanes = turnLanes.split("\\|", -1);
            if (lanes.length < 1 || lanes.length > MAX_LANES) return null;
            List<String> out = new ArrayList<>();
            for (String lane : lanes) {
                boolean l = false, s = false, r = false, u = false;
                for (String raw : lane.split(";")) {
                    switch (raw.trim()) {
                        case "left": case "slight_left": case "sharp_left": l = true; break;
                        case "through": s = true; break;
                        case "right": case "slight_right": case "sharp_right": r = true; break;
                        case "reverse": u = true; break;
                        case "": case "none": case "merge_to_left": case "merge_to_right": break;
                        default: return null; // typo or unknown value: do not guess
                    }
                }
                out.add((l ? "l" : "") + (s ? "s" : "") + (r ? "r" : "") + (u ? "u" : ""));
            }
            return out;
        }

        private static Integer firstInt(String... values) {
            for (String v : values) {
                Integer parsed = parse(v);
                if (parsed != null) return parsed;
            }
            return null;
        }

        private static Integer parse(String value) {
            if (value == null) return null;
            try {
                int n = Integer.parseInt(value.trim());
                return n >= 0 ? n : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        static void selfTest() {
            check(new String[]{"R:?|?", null}, encode(tags("highway", "primary", "oneway", "yes", "lanes", "2"), true));
            check(new String[]{null, "L:?|?|?"}, encode(tags("highway", "primary", "oneway", "-1", "lanes", "3"), false));
            check(new String[]{"L:?", "L:?"}, encode(tags("highway", "secondary", "lanes", "2"), false));
            check(new String[]{"L:?|?", "L:?|?"}, encode(tags("highway", "trunk", "lanes", "4"), false));
            check(null, encode(tags("highway", "secondary", "lanes", "3"), false));
            check(new String[]{"L:?|?", "L:?"}, encode(tags("highway", "secondary", "lanes", "3", "lanes:forward", "2"), false));
            check(new String[]{"L:?", "L:?"},
                encode(tags("highway", "primary", "lanes", "3", "lanes:both_ways", "1"), false));
            check(new String[]{"L:l|s|sr", null},
                encode(tags("highway", "motorway", "lanes", "3", "turn:lanes", "left|through|through;right"), false));
            check(new String[]{"R:|l", "R:s|r"},
                encode(tags("highway", "primary", "turn:lanes:forward", "none|slight_left",
                    "turn:lanes:backward", "through|right"), true));
            // Mismatched count and arrows: the count wins, arrows are dropped.
            check(new String[]{"L:?|?|?", null},
                encode(tags("highway", "primary", "oneway", "yes", "lanes", "3", "turn:lanes", "left|right"), false));
            // Typos are not guessed.
            check(new String[]{"L:?|?", null},
                encode(tags("highway", "primary", "oneway", "yes", "lanes", "2", "turn:lanes", "lef|right"), false));
            check(null, encode(tags("highway", "primary", "oneway", "yes"), false));
            check(null, encode(tags("highway", "primary", "oneway", "yes", "lanes", "12"), false));
            check(new String[]{"L:?|?", null}, encode(tags("highway", "primary", "junction", "roundabout", "lanes", "2"), false));
            check(null, encode(tags("building", "yes", "lanes", "2"), false));
        }

        private static Map<String, String> tags(String... kv) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
            return map;
        }

        private static void check(String[] expected, String[] actual) {
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError("expected " + Arrays.toString(expected) + " got " + Arrays.toString(actual));
            }
        }
    }
}
