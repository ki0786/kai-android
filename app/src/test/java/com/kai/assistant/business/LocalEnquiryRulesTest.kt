package com.kai.assistant.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEnquiryRulesTest {
    @Test
    fun addressEnglishAndHinglish() {
        assertEquals(setOf(Topic.Address), LocalEnquiryRules.offlineTopics("What is your address?"))
        assertEquals(setOf(Topic.Address), LocalEnquiryRules.offlineTopics("shop kahan hai"))
        assertEquals(setOf(Topic.Address), LocalEnquiryRules.offlineTopics("शोरूम कहाँ है"))
    }

    @Test
    fun hoursEnglishAndHinglish() {
        assertEquals(setOf(Topic.Hours), LocalEnquiryRules.offlineTopics("What are your opening hours?"))
        assertEquals(setOf(Topic.Hours), LocalEnquiryRules.offlineTopics("kitne baje tak khula hai"))
        assertEquals(setOf(Topic.Hours), LocalEnquiryRules.offlineTopics("आज खुला है क्या"))
    }

    @Test
    fun rejectsOtherIntentsOffline() {
        assertTrue(LocalEnquiryRules.offlineTopics("sofa price kitna hai").isEmpty())
        assertTrue(LocalEnquiryRules.offlineTopics("do you deliver to Whitefield?").isEmpty())
        assertTrue(LocalEnquiryRules.offlineTopics("when can I visit the showroom?").isEmpty())
        assertTrue(LocalEnquiryRules.offlineTopics("address and sofa price").isEmpty())
    }

    @Test
    fun falsePositivesNotHours() {
        // "delivery time" is other intent, not hours.
        assertTrue(LocalEnquiryRules.offlineTopics("what is the delivery time").isEmpty())
        assertTrue(LocalEnquiryRules.offlineTopics("we live close to the shop").isEmpty())
    }

    @Test
    fun needsOwnerForComplaintsAndNegotiation() {
        assertTrue(LocalEnquiryRules.needsOwner("sofa is damaged, I want a refund"))
        assertTrue(LocalEnquiryRules.needsOwner("last price kam karo"))
        assertTrue(LocalEnquiryRules.needsOwner("mera order kab aayega"))
        assertFalse(LocalEnquiryRules.needsOwner("what is your address"))
    }

    @Test
    fun mediaOnlyAndYes() {
        assertTrue(LocalEnquiryRules.isMediaOnly("📷"))
        assertTrue(LocalEnquiryRules.isMediaOnly("photo"))
        assertFalse(LocalEnquiryRules.isMediaOnly("photo of the sofa set please"))
        assertTrue(LocalEnquiryRules.isYes("haan ji"))
        assertTrue(LocalEnquiryRules.isYes("yes"))
        assertFalse(LocalEnquiryRules.isYes("yes but at 7?"))
        assertFalse(LocalEnquiryRules.isYes("nahi"))
    }
}
