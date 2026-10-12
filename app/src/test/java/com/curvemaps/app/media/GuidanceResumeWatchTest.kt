package com.curvemaps.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A player paused by guidance's own duck is resumed once; nothing else ever is. */
class GuidanceResumeWatchTest {
    private val grace = GuidanceResumeWatch.RESUME_GRACE_MS

    private var now = 0L

    private fun controller(gateway: FakeMediaSessionGateway) =
        MediaCenterController(gateway, FakeLocalVolume(), FakeMediaKeys(), clock = { now })

    @Test
    fun aPlayerThatPausedForThePromptAndStayedPausedIsResumedOnce() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("spotify", isPlaying = true)))
        val control = controller(gateway)

        control.onGuidanceDuckStarted(nowMs = 0)
        gateway.sessions = listOf(session("spotify", isPlaying = false))
        control.onGuidanceDuckEnded(nowMs = 1_000)

        assertEquals(emptyList<String>(), control.resumeAfterGuidance(nowMs = 1_000 + grace - 1))
        assertEquals(listOf("spotify"), control.resumeAfterGuidance(nowMs = 1_000 + grace))
        assertEquals(listOf("spotify" to MediaCommand.PLAY), gateway.transportCalls)
        assertEquals(emptyList<String>(), control.resumeAfterGuidance(nowMs = 1_000 + grace * 2))
        assertEquals(1, gateway.transportCalls.size)
    }

    @Test
    fun aPlayerThatRestartedByItselfIsLeftAlone() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("spotify", isPlaying = true)))
        val control = controller(gateway)
        control.onGuidanceDuckStarted(nowMs = 0)
        control.onGuidanceDuckEnded(nowMs = 0)

        assertEquals(emptyList<String>(), control.resumeAfterGuidance(nowMs = grace))
        assertTrue(gateway.transportCalls.isEmpty())
    }

    @Test
    fun aPlayerThatWasAlreadyPausedBeforeThePromptIsNeverStarted() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("podcast", isPlaying = false)))
        val control = controller(gateway)
        control.onGuidanceDuckStarted(nowMs = 0)
        control.onGuidanceDuckEnded(nowMs = 0)

        assertEquals(emptyList<String>(), control.resumeAfterGuidance(nowMs = grace))
        assertTrue(gateway.transportCalls.isEmpty())
    }

    @Test
    fun aCallOrAnotherAppTakingFocusIsNotOursToUndo() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("spotify", isPlaying = true)))
        val control = controller(gateway)
        control.onGuidanceDuckStarted(nowMs = 0)
        gateway.sessions = listOf(session("spotify", isPlaying = false))
        control.onGuidanceDuckEnded(nowMs = 0)
        control.onGuidanceDuckInterrupted()

        assertEquals(emptyList<String>(), control.resumeAfterGuidance(nowMs = grace))
        assertTrue(gateway.transportCalls.isEmpty())
    }

    @Test
    fun theRidersOwnTransportChoiceWins() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("spotify", isPlaying = true)))
        val control = controller(gateway)
        control.attach()
        control.setPanelOpen(true)
        control.onGuidanceDuckStarted(nowMs = 0)
        control.dispatch(MediaCommand.PAUSE)
        gateway.sessions = listOf(session("spotify", isPlaying = false))
        control.onGuidanceDuckEnded(nowMs = 0)

        assertEquals(emptyList<String>(), control.resumeAfterGuidance(nowMs = grace))
        assertEquals(listOf("spotify" to MediaCommand.PAUSE), gateway.transportCalls)
    }

    @Test
    fun withoutNotificationAccessNothingIsResumedBlindly() {
        val gateway = FakeMediaSessionGateway(access = false, sessions = listOf(session("spotify", isPlaying = true)))
        val control = controller(gateway)
        control.onGuidanceDuckStarted(nowMs = 0)
        gateway.sessions = listOf(session("spotify", isPlaying = false))
        control.onGuidanceDuckEnded(nowMs = 0)

        assertEquals(emptyList<String>(), control.resumeAfterGuidance(nowMs = grace))
        assertTrue(gateway.transportCalls.isEmpty())
    }

    @Test
    fun aPlayerWithoutPlayOrWithUnknownStateIsNotCommanded() {
        val watch = GuidanceResumeWatch(graceMillis = 10)
        watch.onDuckStarted(listOf(session("a", isPlaying = true), session("b", isPlaying = true)), nowMs = 0)
        watch.onDuckEnded(nowMs = 0)
        val after = listOf(
            session("a", isPlaying = false, actions = MediaActionBits.PAUSE),
            session("b", isPlaying = false).copy(playbackKnown = false),
        )
        assertEquals(emptyList<String>(), watch.takeResumable(after, nowMs = 10))
    }

    @Test
    fun aSecondPromptInsideTheGraceWindowExtendsTheSameDuck() {
        val watch = GuidanceResumeWatch(graceMillis = 100)
        watch.onDuckStarted(listOf(session("spotify", isPlaying = true)), nowMs = 0)
        watch.onDuckEnded(nowMs = 0)
        // The player paused for the first prompt; the second sees it paused.
        watch.onDuckStarted(listOf(session("spotify", isPlaying = false)), nowMs = 40)
        assertFalse(watch.isDue(nowMs = 100))
        watch.onDuckEnded(nowMs = 50)

        assertFalse(watch.isDue(nowMs = 149))
        assertEquals(listOf("spotify"), watch.takeResumable(listOf(session("spotify", isPlaying = false)), nowMs = 150))
    }

    @Test
    fun anEndWithoutAStartSchedulesNothing() {
        val watch = GuidanceResumeWatch(graceMillis = 10)
        watch.onDuckEnded(nowMs = 0)
        assertFalse(watch.isDue(nowMs = 1_000))
    }

    @Test
    fun aPauseObservedRightAfterTheFocusRequestIsOurs() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("spotify", isPlaying = true)))
        val control = controller(gateway)
        control.attach()
        now = 1_000
        control.onGuidanceDuckStarted(nowMs = now)
        now = 1_200
        gateway.sessions = listOf(session("spotify", isPlaying = false))
        gateway.fireSessionsChanged()
        now = 4_000
        control.onGuidanceDuckEnded(nowMs = now)

        assertEquals(listOf("spotify"), control.resumeAfterGuidance(nowMs = now + grace))
    }

    @Test
    fun theRidersOwnPauseFromAHeadsetDuringThePromptIsKept() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("spotify", isPlaying = true)))
        val control = controller(gateway)
        control.attach()
        now = 1_000
        control.onGuidanceDuckStarted(nowMs = now)
        // Ducked, still playing; the rider presses pause on the intercom later.
        now = 3_500
        gateway.sessions = listOf(session("spotify", isPlaying = false))
        gateway.fireSessionsChanged()
        now = 4_000
        control.onGuidanceDuckEnded(nowMs = now)

        assertEquals(emptyList<String>(), control.resumeAfterGuidance(nowMs = now + grace))
        assertTrue(gateway.transportCalls.isEmpty())
    }

    @Test
    fun aPauseAfterThePromptEndedIsTheRiders() {
        val watch = GuidanceResumeWatch(graceMillis = 2_000)
        watch.onDuckStarted(listOf(session("spotify", isPlaying = true)), nowMs = 0)
        watch.onSessionsObserved(listOf(session("spotify", isPlaying = true)), nowMs = 200)
        watch.onDuckEnded(nowMs = 1_000)
        watch.onSessionsObserved(listOf(session("spotify", isPlaying = false)), nowMs = 1_100)
        assertEquals(emptyList<String>(), watch.takeResumable(listOf(session("spotify", isPlaying = false)), nowMs = 3_000))
    }

    @Test
    fun aPlayerThatRestartedThenPausedAgainIsLeftAlone() {
        val watch = GuidanceResumeWatch(graceMillis = 2_000)
        watch.onDuckStarted(listOf(session("spotify", isPlaying = true)), nowMs = 0)
        watch.onSessionsObserved(listOf(session("spotify", isPlaying = false)), nowMs = 100)
        watch.onSessionsObserved(listOf(session("spotify", isPlaying = true)), nowMs = 900)
        watch.onSessionsObserved(listOf(session("spotify", isPlaying = false)), nowMs = 1_400)
        watch.onDuckEnded(nowMs = 2_000)
        assertEquals(emptyList<String>(), watch.takeResumable(listOf(session("spotify", isPlaying = false)), nowMs = 4_000))
    }
}
