package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spoken names, as speech recognition writes them, against a realistic phonebook.
 * Each must reach the closest saved contact; names that sound unrelated must not.
 */
class NameMatchingTableTest {
    private val book = listOf(
        "Mohammed Kaif", "Muhammad Kev", "Nuruddin Shaikh", "Mom", "Dad", "Ayesha Khan",
        "Rahul Sharma", "Priya Singh", "Abdul Rahman", "Imran Bhai", "Zubair Ahmed",
        "Suresh Plumber", "Fatima", "Sameer Office", "Dr. Mehta",
    ).mapIndexed { index, name ->
        ContactPhone(index + 1L, name, "98765${(10000 + index)}", PhoneKind.Mobile)
    }

    private val expected = listOf(
        "mohammed kaif" to "Mohammed Kaif",
        "mohamed kaif" to "Mohammed Kaif",
        "muhammad kaif" to "Mohammed Kaif",
        "mohd kaif" to "Mohammed Kaif",
        "mohamed cab" to "Mohammed Kaif",
        "mohammed kaife" to "Mohammed Kaif",
        "kaif" to "Mohammed Kaif",
        "kev" to "Muhammad Kev",
        "muhammad kev" to "Muhammad Kev",
        "mohammad ke" to "Muhammad Kev",
        "muhammad kef" to "Muhammad Kev",
        "mohammed kev" to "Muhammad Kev",
        "nuruddin" to "Nuruddin Shaikh",
        "nurdine" to "Nuruddin Shaikh",
        "noorudin" to "Nuruddin Shaikh",
        "nooruddin shaikh" to "Nuruddin Shaikh",
        "nuruddin sheikh" to "Nuruddin Shaikh",
        "ayesha" to "Ayesha Khan",
        "aisha" to "Ayesha Khan",
        "aysha khan" to "Ayesha Khan",
        "rahul" to "Rahul Sharma",
        "rahool sharma" to "Rahul Sharma",
        "priya" to "Priya Singh",
        "pria" to "Priya Singh",
        "abdul rehman" to "Abdul Rahman",
        "abdul" to "Abdul Rahman",
        "imran" to "Imran Bhai",
        "imran bhai" to "Imran Bhai",
        "zubair" to "Zubair Ahmed",
        "zubeir" to "Zubair Ahmed",
        "suresh" to "Suresh Plumber",
        "plumber" to "Suresh Plumber",
        "fatima" to "Fatima",
        "fathima" to "Fatima",
        "sameer" to "Sameer Office",
        "samir" to "Sameer Office",
        "mehta" to "Dr. Mehta",
        "doctor mehta" to "Dr. Mehta",
        "mom" to "Mom",
        "dad" to "Dad",
        "Mohammed Kaif" to "Mohammed Kaif",
        "MOHAMMED KAIF" to "Mohammed Kaif",
    )

    @Test
    fun everySpokenNameReachesTheClosestContact() {
        val failures = expected.mapNotNull { (spoken, name) ->
            when (val result = ContactMatcher.match(spoken, book, preferredKind = null)) {
                is ContactMatchResult.One -> if (result.displayName == name) null else "\"$spoken\" -> ${result.displayName}, expected $name"
                else -> "\"$spoken\" -> $result, expected $name"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun namesThatSoundDifferentAreNotGuessed() {
        listOf("tom", "john", "xavier", "kevin peterson", "lakshmi").forEach { spoken ->
            val result = ContactMatcher.match(spoken, book, preferredKind = null)
            assertTrue("\"$spoken\" -> $result", result is ContactMatchResult.None)
        }
    }

    @Test
    fun aTrueTieAsksInsteadOfGuessing() {
        val twins = listOf(
            ContactPhone(1, "Kaif Office", "111", PhoneKind.Work),
            ContactPhone(2, "Kaif Home", "222", PhoneKind.Home),
        )
        assertTrue(ContactMatcher.match("kaif", twins, preferredKind = null) is ContactMatchResult.ManyContacts)
    }

    @Test
    fun titlesAndAccentsDoNotGetInTheWay() {
        val titled = listOf(ContactPhone(1, "Kaif Bhai", "111", PhoneKind.Mobile), ContactPhone(2, "José", "222", PhoneKind.Mobile))
        assertEquals("Kaif Bhai", (ContactMatcher.match("kaif", titled, null) as ContactMatchResult.One).displayName)
        assertEquals("José", (ContactMatcher.match("jose", titled, null) as ContactMatchResult.One).displayName)
    }

    @Test
    fun theReadingThatMatchesAContactWins() {
        val options = WhatsAppUtterance.parse("message kaif come home")!!.options
        val ranked = WhatsAppResolver.rankByContact(options, book)
        assertEquals(WhatsAppUtterance.Option("kaif", "come home"), ranked.first())
        val resolved = WhatsAppResolver.resolve(options, book) as WhatsAppResolution.Ready
        assertEquals("Mohammed Kaif", resolved.displayName)
        assertEquals("come home", resolved.message)

        val long = WhatsAppResolver.resolve(WhatsAppUtterance.parse("whatsapp mohammed kaif hi")!!.options, book)
        assertEquals("hi", (long as WhatsAppResolution.Ready).message)
    }

    @Test
    fun appNamesMatchLooselyToo() {
        val apps = listOf(
            "Instagram", "WhatsApp", "YouTube", "Spotify", "Google Pay", "Chrome", "Phone", "Messages", "Settings",
        ).map { AppLabel(it, "pkg.${it.lowercase().replace(" ", "")}") }
        assertEquals("pkg.instagram", pickApp("insta", apps))
        assertEquals("pkg.whatsapp", pickApp("whats app", apps))
        assertEquals("pkg.spotify", pickApp("spotfy", apps))
        assertEquals("pkg.googlepay", pickApp("gpay", apps))
        assertEquals("pkg.youtube", pickApp("yt", apps))
        assertEquals("pkg.chrome", pickApp("browser", apps))
        assertEquals("pkg.messages", pickApp("sms", apps))
        assertNull(pickApp("netflix", apps))
    }

    @Test
    fun whatsAppNumbersGetTheCountryCode() {
        assertEquals("919876543210", internationalDigits("+91 98765 43210", "91"))
        assertEquals("919876543210", internationalDigits("098765 43210", "91"))
        assertEquals("919876543210", internationalDigits("9876543210", "91"))
        assertEquals("919876543210", internationalDigits("0091 98765 43210", "91"))
        assertEquals("9876543210", internationalDigits("9876543210", null))
        assertEquals("91", callingCodeFor("IN"))
        assertEquals("919876543210@s.whatsapp.net", messageJid("98765-43210", "91"))
    }
}
