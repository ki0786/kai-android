package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAppsTest {
    private val router = LocalIntentRouter()

    @Test
    fun installedAppsOpenWithoutAsking() {
        val youtube = router.match("open youtube")
        assertEquals(LocalCommand.LaunchAppByName("youtube"), youtube!!.command)
        assertFalse(youtube.requiresConfirmation)
        assertTrue(youtube.command.staysOnDevice())

        val whatsApp = router.match("open whatsapp")
        assertEquals(LocalCommand.LaunchAppByName("whatsapp"), whatsApp!!.command)
        assertFalse(whatsApp.requiresConfirmation)
        assertTrue(whatsApp.command.staysOnDevice())

        val camera = router.match("open the camera")
        assertTrue(camera!!.command is LocalCommand.OpenCamera)
        assertFalse(camera.requiresConfirmation)

        val maps = router.match("open maps to the supplier")
        assertEquals(LocalCommand.OpenMaps("the supplier"), maps!!.command)
        assertFalse(maps.requiresConfirmation)
        assertNull(researchQuery("open youtube"))

        val okay = router.match("okay open youtube")
        assertEquals(LocalCommand.LaunchAppByName("youtube"), okay!!.command)
        assertEquals(
            LocalCommand.LaunchAppByName("youtube"),
            router.match("ok, open youtube")!!.command,
        )
        assertEquals("YouTube", spokenLaunchName("com.google.android.youtube", "youtube"))
        assertEquals("YouTube", spokenLaunchName("com.google.android.youtube", ""))
        assertFalse(spokenLaunchName("com.google.android.youtube", "youtube").contains('.'))
    }

    @Test
    fun youtubeSearchUsesAResultsUrl() {
        val match = router.match("search believer on youtube")
        val command = match!!.command as LocalCommand.SearchYouTube
        assertEquals("believer", command.query)
        assertFalse(match.requiresConfirmation)
        assertTrue(command.staysOnDevice())
        assertEquals(
            "https://www.youtube.com/results?search_query=believer",
            youtubeResultsUrl(command.query),
        )
        assertEquals(
            "https://www.youtube.com/results?search_query=believer%20song",
            youtubeResultsUrl("believer song"),
        )
        assertEquals("believer", parseYouTubeSearch("search youtube for believer"))
        assertNull(researchQuery("search believer on youtube"))
        assertNull(planLocalSteps("search believer on youtube") { router.match(it) })
        assertNull(router.match("search the web for believer"))
    }
}
