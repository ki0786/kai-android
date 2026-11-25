package com.kai.assistant.business

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class AnswerGuardTest {
    private val info = BusinessInfo(
        businessName = "Sagar Furniture",
        address = "123 MG Road, Bengaluru",
        hours = OpeningHours(
            mapOf(DayOfWeek.MONDAY to DayHours(10 * 60, 20 * 60)),
        ),
        phone = "9876543210",
        website = "https://sagarfurniture.com",
        products = listOf(Product("Sofa set", "₹25,000 – ₹80,000"), Product("Bed", "15000-45000")),
        deliveryArea = "Bengaluru and nearby",
        offers = "Festival polish free on sofa sets",
    )

    @Test
    fun acceptsGroundedReply() {
        val reply = "Our showroom address: 123 MG Road, Bengaluru\nOur website: sagarfurniture.com"
        assertTrue(AnswerGuard.check(reply, info, setOf(Topic.Address, Topic.Website)).ok)
    }

    @Test
    fun rejectsInventedPrice() {
        val reply = "Sofa set starts at ₹12,000 only today"
        assertFalse(AnswerGuard.check(reply, info, setOf(Topic.Price)).ok)
    }

    @Test
    fun rejectsUnknownLink() {
        val reply = "See catalog at https://evil.example.com/sofas"
        assertFalse(AnswerGuard.check(reply, info, setOf(Topic.Catalog)).ok)
    }

    @Test
    fun rejectsStockAndDiscountClaims() {
        assertFalse(AnswerGuard.check("Sofa is in stock", info).ok)
        assertFalse(AnswerGuard.check("We give 20% discount", info).ok)
        assertFalse(AnswerGuard.check("Delivery tomorrow free", info, setOf(Topic.Delivery)).ok)
    }

    @Test
    fun hoursNumberDoesNotCountAsPrice() {
        // "10" and "20" appear in opening hours; mentioning them without a currency claim is fine.
        val reply = "Showroom timings: Mon: 10:00 AM – 8:00 PM"
        assertTrue(AnswerGuard.check(reply, info, setOf(Topic.Hours)).ok)
    }

    @Test
    fun acceptsVisitWithMatchingDigits() {
        val visit = LocalDateTime.of(2026, 11, 26, 11, 0)
        val reply = "Would Thursday, 26 Nov at 11:00 AM suit you?"
        assertTrue(AnswerGuard.check(reply, info, visit = visit).ok)
    }

    @Test
    fun acceptsOfferWordWhenTopicIsOffers() {
        val reply = "Current offers: Festival polish free on sofa sets"
        assertTrue(AnswerGuard.check(reply, info, setOf(Topic.Offers)).ok)
    }
}
