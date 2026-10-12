package com.curvemaps.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeModelTest {
    private val present = WelcomeMapStatus(dataPresent = true, regionName = "Queensland")
    private val noTiles = WelcomeMapStatus(dataPresent = true, regionName = "Queensland", tilesInstalled = false)
    private val missing = WelcomeMapStatus(dataPresent = false, regionName = "No map installed")

    @Test
    fun pagesRunWelcomeLocationMediaMapAndOnlyMapIsLast() {
        assertEquals(
            listOf(WelcomePage.WELCOME, WelcomePage.SAFETY, WelcomePage.LOCATION, WelcomePage.MEDIA, WelcomePage.MAP),
            WelcomePage.entries,
        )
        assertEquals(listOf(WelcomePage.MAP), WelcomePage.entries.filter { it.isLast })
        assertNull(WelcomePage.WELCOME.previous())
        assertNull(WelcomePage.MAP.next())
        WelcomePage.entries.zipWithNext().forEach { (a, b) ->
            assertEquals(b, a.next())
            assertEquals(a, b.previous())
        }
    }

    @Test
    fun safetyPageCannotBeSkippedOrSwipedPastUntilAcknowledged() {
        for (page in WelcomePage.entries) {
            assertFalse("$page skip before ack", WelcomeModel.showSkip(page, safetyAcknowledged = false))
            assertEquals("$page skip after ack", !page.isLast, WelcomeModel.showSkip(page, safetyAcknowledged = true))
            assertTrue(WelcomeModel.swipeEnabled(page, safetyAcknowledged = true))
        }
        assertTrue(WelcomeModel.swipeEnabled(WelcomePage.WELCOME, safetyAcknowledged = false))
        assertFalse(WelcomeModel.swipeEnabled(WelcomePage.SAFETY, safetyAcknowledged = false))
        assertTrue(WelcomePage.SAFETY < WelcomePage.LOCATION)
        assertTrue(WelcomeModel.SAFETY_BODY.contains("eyes on the road"))
        assertTrue(WelcomeModel.SAFETY_DATA_NOTE.contains("road rules"))
    }

    @Test
    fun locationIsRequestedOnlyOnItsOwnPageOnceAndNeverWhenGranted() {
        for (page in WelcomePage.entries) for (granted in listOf(true, false)) for (asked in listOf(true, false)) {
            val expected = page == WelcomePage.LOCATION && !granted && !asked
            assertEquals("$page granted=$granted asked=$asked", expected,
                WelcomeModel.shouldAutoRequestLocation(page, granted, asked))
        }
    }

    @Test
    fun primaryButtonActsUntilGrantedThenAdvances() {
        val p = WelcomeModel::primaryAction
        assertEquals(WelcomePrimary.NEXT, p(WelcomePage.WELCOME, false, false, missing))
        assertEquals(WelcomePrimary.ACKNOWLEDGE_SAFETY, p(WelcomePage.SAFETY, true, true, present))
        assertEquals(WelcomePrimary.ALLOW_LOCATION, p(WelcomePage.LOCATION, false, true, present))
        assertEquals(WelcomePrimary.NEXT, p(WelcomePage.LOCATION, true, false, present))
        assertEquals(WelcomePrimary.ENABLE_MEDIA, p(WelcomePage.MEDIA, true, false, present))
        assertEquals(WelcomePrimary.NEXT, p(WelcomePage.MEDIA, false, true, present))
        assertEquals(WelcomePrimary.CHOOSE_MAP_FILE, p(WelcomePage.MAP, true, true, missing))
        assertEquals(WelcomePrimary.FINISH, p(WelcomePage.MAP, false, false, present))
        assertEquals(WelcomePrimary.CHOOSE_MAP_FILE, p(WelcomePage.MAP, true, true, noTiles))
    }

    @Test
    fun statusTextIsHonestAboutEachPermission() {
        assertEquals("Location allowed", WelcomeModel.locationStatus(granted = true, asked = false))
        assertEquals("Not allowed yet", WelcomeModel.locationStatus(granted = false, asked = false))
        assertTrue(WelcomeModel.locationStatus(granted = false, asked = true).contains("later"))
        assertEquals("Media control enabled", WelcomeModel.mediaStatus(true))
        assertEquals("Not enabled", WelcomeModel.mediaStatus(false))
        assertTrue(WelcomeModel.MEDIA_BODY.contains("never read"))
    }

    @Test
    fun mapPageNamesTheReadyRegionOrExplainsMotomapFiles() {
        assertTrue(WelcomeModel.mapBody(present).startsWith("Queensland is ready"))
        // A bundled build has routing/search but no drawn map until a file is chosen.
        assertFalse(noTiles.ready)
        assertTrue(WelcomeModel.mapBody(noTiles).contains("map file"))
        assertFalse(WelcomeModel.mapBody(noTiles).contains("is ready"))
        val body = WelcomeModel.mapBody(missing)
        assertTrue(body.contains(".motomap"))
        assertFalse(body.contains("No map installed is ready"))
        assertTrue(WelcomeModel.MAP_HOW_TO.contains("Load map file"))
    }

    @Test
    fun mediaCopySplitsIntoLeadAndCardRowsWithoutLosingAnyWords() {
        assertEquals(
            "${WelcomeModel.MEDIA_LEAD} ${WelcomeModel.MEDIA_PRIVACY_NOTE} ${WelcomeModel.MEDIA_OPTIONAL_NOTE}",
            WelcomeModel.MEDIA_BODY,
        )
        assertTrue(WelcomeModel.MEDIA_LEAD.contains("pause, skip and resume"))
        assertTrue(WelcomeModel.MEDIA_PRIVACY_NOTE.contains("never read"))
        assertTrue(WelcomeModel.MEDIA_OPTIONAL_NOTE.contains("phone volume"))
    }

    @Test
    fun welcomeFeatureCardAndMapChipsMatchTheDesign() {
        assertEquals(3, WelcomeModel.WELCOME_FEATURES.size)
        assertTrue(WelcomeModel.WELCOME_FEATURES.first().contains("Seven ride styles"))
        assertEquals(listOf("Basemap", "Routing", "Place search"), WelcomeModel.MAP_PARTS)
    }

    @Test
    fun everyPageHasItsDesignedExpressiveHero() {
        fun spec(p: WelcomePage) = WelcomeHero.spec(p)
        assertEquals(HeroShape.COOKIE_9_SIDED, spec(WelcomePage.WELCOME).shape)
        assertEquals(HeroTone.PRIMARY_CONTAINER, spec(WelcomePage.WELCOME).tone)
        assertEquals(0, spec(WelcomePage.WELCOME).glyphDp) // custom twisty-road art
        assertEquals(HeroShape.CLOVER_4_LEAF, spec(WelcomePage.SAFETY).shape)
        assertEquals(96, spec(WelcomePage.SAFETY).glyphDp)
        assertEquals(HeroShape.COOKIE_6_SIDED, spec(WelcomePage.LOCATION).shape)
        assertEquals(HeroTone.SECONDARY_CONTAINER, spec(WelcomePage.LOCATION).tone)
        assertEquals(HeroShape.SOFT_BURST, spec(WelcomePage.MEDIA).shape)
        assertEquals(72, spec(WelcomePage.MEDIA).glyphDp)
        assertEquals(HeroShape.COOKIE_9_SIDED, spec(WelcomePage.MAP).shape)
        assertEquals(HeroTone.TERTIARY_CONTAINER, spec(WelcomePage.MAP).tone)
        assertEquals(200, spec(WelcomePage.MAP).sizeDp)
        WelcomePage.entries.filter { it != WelcomePage.MAP }.forEach { assertEquals(220, spec(it).sizeDp) }
        WelcomePage.entries.filter { it != WelcomePage.WELCOME }.forEach { assertTrue(spec(it).glyphDp >= 48) }
    }

    @Test
    fun heroTurnsWithTheSwipeUnlessMotionIsReduced() {
        assertEquals(0f, WelcomeHero.rotationDegrees(0f, reducedMotion = false), 0f)
        assertEquals(WelcomeHero.DEGREES_PER_PAGE / 2, WelcomeHero.rotationDegrees(0.5f, false), 1e-4f)
        assertEquals(-WelcomeHero.DEGREES_PER_PAGE, WelcomeHero.rotationDegrees(-1f, false), 1e-4f)
        // Far-away pages do not spin on without bound.
        assertEquals(WelcomeHero.DEGREES_PER_PAGE, WelcomeHero.rotationDegrees(4f, false), 1e-4f)
        assertEquals(0f, WelcomeHero.rotationDegrees(0.7f, reducedMotion = true), 0f)
    }
}
