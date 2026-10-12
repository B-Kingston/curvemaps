package com.curvemaps.app.onboarding

/**
 * First-run welcome carousel: the pages in order, and the pure decisions each
 * page makes. Every permission is asked on the page that explains it, never as
 * a stacked startup dialog; the composable ([WelcomeCarousel]) only renders
 * these decisions.
 */
enum class WelcomePage(val title: String) {
    WELCOME("Welcome to CurveMaps"),
    SAFETY("Ride safely"),
    LOCATION("Your location"),
    MEDIA("Music while you ride"),
    MAP("Offline maps"),
    ;

    val isLast: Boolean get() = this == entries.last()

    fun next(): WelcomePage? = entries.getOrNull(ordinal + 1)

    fun previous(): WelcomePage? = entries.getOrNull(ordinal - 1)
}

/** What the planner should do once the carousel closes. */
enum class WelcomeExit { NONE, CHOOSE_MAP_FILE, OPEN_MAPS }

/**
 * Map data state as the map page reports it: [dataPresent] is routing + search,
 * [tilesInstalled] the drawn basemap. Bundled builds carry only the former.
 */
data class WelcomeMapStatus(
    val dataPresent: Boolean,
    val regionName: String,
    val tilesInstalled: Boolean = dataPresent,
) {
    val ready: Boolean get() = dataPresent && tilesInstalled
}

object WelcomeModel {

    const val WELCOME_BODY =
        "Plan twisty motorcycle rides and follow them with voice guidance — " +
            "routing, search and maps all work offline."
    const val SAFETY_BODY =
        "Keep your eyes on the road. Plan before you set off, let voice guidance do the " +
            "talking, mount the phone securely and only touch it when stopped."
    const val SAFETY_DATA_NOTE =
        "Routes come from OpenStreetMap and can be wrong or out of date — closures, " +
            "surfaces and conditions aren't known. Obey road rules and signs, and ride to the conditions."
    const val LOCATION_BODY =
        "Lets the map start where you are and guides you during a ride. " +
            "It stays on this phone."
    /** The media page shows [MEDIA_LEAD] as its body and the two notes as card rows. */
    const val MEDIA_LEAD =
        "Notification access lets the ride screen pause, skip and resume your music player."
    const val MEDIA_PRIVACY_NOTE = "Notification contents are never read."
    const val MEDIA_OPTIONAL_NOTE = "Optional — phone volume works without it."
    const val MEDIA_BODY = "$MEDIA_LEAD $MEDIA_PRIVACY_NOTE $MEDIA_OPTIONAL_NOTE"

    /** The welcome page's feature card, in order. */
    val WELCOME_FEATURES: List<String> = listOf(
        "Seven ride styles, Fastest to Max\u00A0twisty",
        "Spoken turn-by-turn guidance",
        "No signal needed on the road",
    )

    /** What one .motomap file carries: the chips on the map page. */
    val MAP_PARTS: List<String> = listOf("Basemap", "Routing", "Place search")
    const val MAP_NEEDED_BODY =
        "CurveMaps needs one .motomap file per region: map, routing and place search together."
    const val MAP_HOW_TO =
        "Get one from the CurveMaps release page (queensland.motomap), or download " +
            "a region in Maps. Then choose the file here or in Settings → Load map file."

    /**
     * The safety page must be acknowledged before anything can be skipped, so
     * every rider sees it once: Skip stays hidden and swiping cannot pass it
     * until "I understand".
     */
    fun showSkip(page: WelcomePage, safetyAcknowledged: Boolean): Boolean =
        safetyAcknowledged && !page.isLast

    fun swipeEnabled(page: WelcomePage, safetyAcknowledged: Boolean): Boolean =
        safetyAcknowledged || page < WelcomePage.SAFETY

    /**
     * The location request fires once when the rider arrives on its page, so the
     * system dialog appears next to its explanation. A denial is not re-asked
     * automatically; the page's button asks again on demand.
     */
    fun shouldAutoRequestLocation(page: WelcomePage, granted: Boolean, alreadyAsked: Boolean): Boolean =
        page == WelcomePage.LOCATION && !granted && !alreadyAsked

    fun locationStatus(granted: Boolean, asked: Boolean): String = when {
        granted -> "Location allowed"
        asked -> "Not allowed — you can allow it later from START or Current location."
        else -> "Not allowed yet"
    }

    fun mediaStatus(granted: Boolean): String =
        if (granted) "Media control enabled" else "Not enabled"

    fun mapBody(status: WelcomeMapStatus): String = when {
        status.ready ->
            "${status.regionName} is ready. Add other regions any time from Settings → Maps."
        status.dataPresent ->
            "Routing and search for ${status.regionName} are built in; the map itself needs a map file."
        else -> MAP_NEEDED_BODY
    }

    /** The large button on each page; it either acts or advances. */
    fun primaryAction(
        page: WelcomePage,
        locationGranted: Boolean,
        mediaGranted: Boolean,
        map: WelcomeMapStatus,
    ): WelcomePrimary = when (page) {
        WelcomePage.WELCOME -> WelcomePrimary.NEXT
        WelcomePage.SAFETY -> WelcomePrimary.ACKNOWLEDGE_SAFETY
        WelcomePage.LOCATION -> if (locationGranted) WelcomePrimary.NEXT else WelcomePrimary.ALLOW_LOCATION
        WelcomePage.MEDIA -> if (mediaGranted) WelcomePrimary.NEXT else WelcomePrimary.ENABLE_MEDIA
        WelcomePage.MAP -> if (map.ready) WelcomePrimary.FINISH else WelcomePrimary.CHOOSE_MAP_FILE
    }
}

enum class WelcomePrimary(val label: String) {
    NEXT("Next"),
    ACKNOWLEDGE_SAFETY("I understand"),
    ALLOW_LOCATION("Allow location"),
    ENABLE_MEDIA("Enable notification access"),
    CHOOSE_MAP_FILE("Choose map file"),
    FINISH("Start planning"),
}
