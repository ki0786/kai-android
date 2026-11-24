package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {
    @Test
    fun exactNameBeatsLongerName() {
        val result = ContactMatcher.match(
            "kaif",
            listOf(
                ContactPhone(1, "Kaif", "111", PhoneKind.Mobile),
                ContactPhone(2, "Kaif Ahmed", "222", PhoneKind.Mobile),
            ),
            preferredKind = null,
        )
        val one = result as ContactMatchResult.One
        assertEquals("Kaif", one.displayName)
        assertEquals("111", one.number)
    }

    @Test
    fun singleTokenMatchesOnlyContact() {
        val result = ContactMatcher.match(
            "kaif",
            listOf(ContactPhone(2, "Kaif Ahmed", "+91 222", PhoneKind.Mobile)),
            preferredKind = null,
        )
        val one = result as ContactMatchResult.One
        assertEquals("Kaif Ahmed", one.displayName)
        assertEquals("+91 222", one.number)
    }

    @Test
    fun clippedNamePicksTheClosestButADifferentSoundDoesNot() {
        val clipped = ContactMatcher.match(
            "kai",
            listOf(ContactPhone(1, "Kaif", "111", PhoneKind.Mobile)),
            preferredKind = null,
        )
        assertEquals("Kaif", (clipped as ContactMatchResult.One).displayName)

        val result = ContactMatcher.match(
            "tom",
            listOf(ContactPhone(1, "Mom", "111", PhoneKind.Mobile)),
            preferredKind = null,
        )
        assertTrue(result is ContactMatchResult.None)
        assertEquals(
            "I couldn't find a contact named Tom on this phone.",
            ContactMatcher.describe(result),
        )
    }

    @Test
    fun severalContactsStayUnresolved() {
        val result = ContactMatcher.match(
            "kaif",
            listOf(
                ContactPhone(1, "Kaif Khan", "222", PhoneKind.Mobile),
                ContactPhone(2, "Kaif Ahmed", "111", PhoneKind.Home),
            ),
            preferredKind = null,
        )
        assertEquals(
            "I found several contacts matching Kaif: Kaif Ahmed (111), Kaif Khan (222). Say the full name.",
            ContactMatcher.describe(result),
        )
    }

    @Test
    fun prefersMobileUnlessAskedOtherwise() {
        val phones = listOf(
            ContactPhone(1, "Mom", "111", PhoneKind.Home),
            ContactPhone(1, "Mom", "222", PhoneKind.Mobile),
        )
        val mobile = ContactMatcher.match("mom", phones, preferredKind = null) as ContactMatchResult.One
        assertEquals("222", mobile.number)

        val home = ContactMatcher.match("mom", phones, PhoneKind.Home) as ContactMatchResult.One
        assertEquals("111", home.number)
        assertEquals(
            "Call Mom's home number at 111?",
            ContactMatcher.confirmationPrompt(home.displayName, home.number, PhoneKind.Home),
        )
    }

    @Test
    fun missingRequestedKindAndDuplicateNumbers() {
        val missing = ContactMatcher.match(
            "mom",
            listOf(ContactPhone(1, "Mom", "222", PhoneKind.Mobile)),
            PhoneKind.Home,
        )
        assertTrue(missing is ContactMatchResult.MissingKind)

        val duplicate = ContactMatcher.match(
            "mom",
            listOf(
                ContactPhone(1, "Mom", "222-222", PhoneKind.Mobile),
                ContactPhone(1, "Mom", "222222", PhoneKind.Other),
            ),
            preferredKind = null,
        ) as ContactMatchResult.One
        assertEquals("222-222", duplicate.number)
    }

    @Test
    fun severalNumbersAreListed() {
        val result = ContactMatcher.match(
            "kaif",
            listOf(
                ContactPhone(1, "Kaif", "111", PhoneKind.Mobile),
                ContactPhone(1, "Kaif", "333", PhoneKind.Mobile),
            ),
            preferredKind = null,
        )
        assertEquals(
            "Kaif has several numbers: mobile 111, mobile 333. Say call Kaif and the number you want.",
            ContactMatcher.describe(result),
        )
    }

    @Test
    fun closeSpokenNamesPickTheNearestContact() {
        val kef = ContactMatcher.match(
            "muhammad kev",
            listOf(
                ContactPhone(1, "Muhammad Ali", "111", PhoneKind.Mobile),
                ContactPhone(2, "Muhammad Kef", "222", PhoneKind.Mobile),
            ),
            preferredKind = null,
        ) as ContactMatchResult.One
        assertEquals("Muhammad Kef", kef.displayName)

        val muhammad = ContactMatcher.match(
            "mohamed",
            listOf(ContactPhone(3, "Muhammad", "333", PhoneKind.Mobile)),
            preferredKind = null,
        ) as ContactMatchResult.One
        assertEquals("Muhammad", muhammad.displayName)

        val nuruddin = ContactMatcher.match(
            "nurdine",
            listOf(ContactPhone(4, "Nuruddin", "444", PhoneKind.Mobile)),
            preferredKind = null,
        ) as ContactMatchResult.One
        assertEquals("Nuruddin", nuruddin.displayName)
    }

    @Test
    fun accentFoldingMatchesJose() {
        val result = ContactMatcher.match(
            "jose",
            listOf(ContactPhone(1, "José", "444", PhoneKind.Mobile)),
            preferredKind = null,
        )
        assertTrue(result is ContactMatchResult.One)
    }
}
