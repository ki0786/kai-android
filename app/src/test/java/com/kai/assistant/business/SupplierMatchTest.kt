package com.kai.assistant.business

import com.kai.assistant.books.MaterialStock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplierMatchTest {
    private val teak = MaterialStock("1", "Teak", 5.0, "pcs", "Sharma Timber", 2.0)
    private val ply = MaterialStock("2", "Plywood", 4.0, "sheet", "Anand Ply", 1.0)
    private val mystery = MaterialStock("3", "Fevicol", 1.0, "kg", "", 0.0)

    private val sharma = PartyTag(
        chatKey = "id:919800000001@s.whatsapp.net",
        displayName = "Sharma Timber",
        role = PartyRole.SUPPLIER,
    )
    private val anand = PartyTag(
        chatKey = "id:919800000002@s.whatsapp.net",
        displayName = "Anand Ply",
        role = PartyRole.SUPPLIER,
    )
    private val stranger = PartyTag(
        chatKey = "id:919800000099@s.whatsapp.net",
        displayName = "Ramesh Hardware",
        role = PartyRole.SUPPLIER,
    )

    @Test
    fun stockSupplierMatchesTheTaggedChat() {
        val match = SupplierMatch.matchOne(
            MaterialLine("Teak", 10.0, "pcs"),
            listOf(teak, ply),
            listOf(sharma, anand, stranger),
        )
        assertTrue(match is MaterialMatch.Ready)
        match as MaterialMatch.Ready
        assertEquals("Sharma Timber", match.supplier.displayName)
        assertEquals("919800000001", match.digits)
    }

    @Test
    fun eachMaterialKeepsItsOwnSavedSupplier() {
        val matches = SupplierMatch.match(
            listOf(MaterialLine("Teak", 10.0, ""), MaterialLine("Plywood", 4.0, "sheet")),
            listOf(teak, ply),
            listOf(sharma, anand),
        )
        val names = matches.filterIsInstance<MaterialMatch.Ready>().map { it.supplier.displayName }
        assertEquals(listOf("Sharma Timber", "Anand Ply"), names)
    }

    @Test
    fun unknownMaterialDoesNotPickASupplier() {
        val match = SupplierMatch.matchOne(
            MaterialLine("Gold Leaf", 2.0, ""),
            listOf(teak),
            listOf(sharma, stranger),
        )
        assertTrue(match is MaterialMatch.UnknownMaterial)
    }

    @Test
    fun aSupplierWhoIsNotTaggedIsNotMessaged() {
        val stock = teak.copy(supplier = "Ramesh")
        val match = SupplierMatch.matchOne(
            MaterialLine("Teak", 2.0, ""),
            listOf(stock),
            listOf(sharma),
        )
        assertTrue(match is MaterialMatch.UnknownSupplier)
        match as MaterialMatch.UnknownSupplier
        assertTrue(match.reason.contains("not a tagged Supplier"))
    }

    @Test
    fun aTaggedSupplierWithoutANumberIsNotMessaged() {
        val nameless = sharma.copy(chatKey = "name:sharma timber")
        val match = SupplierMatch.matchOne(
            MaterialLine("Teak", 2.0, ""),
            listOf(teak),
            listOf(nameless),
        )
        assertTrue(match is MaterialMatch.UnknownSupplier)
        match as MaterialMatch.UnknownSupplier
        assertTrue(match.reason.contains("no WhatsApp number"))
    }

    @Test
    fun aLabourTagIsNotASupplier() {
        val labour = sharma.copy(role = PartyRole.LABOUR)
        val match = SupplierMatch.matchOne(
            MaterialLine("Teak", 2.0, ""),
            listOf(teak),
            listOf(labour, stranger),
        )
        assertTrue(match is MaterialMatch.UnknownSupplier)
    }

    @Test
    fun blankSupplierOnTheStockLineIsUnknown() {
        val match = SupplierMatch.matchOne(
            MaterialLine("Fevicol", 1.0, "kg"),
            listOf(mystery),
            listOf(sharma),
        )
        assertTrue(match is MaterialMatch.UnknownSupplier)
    }

    @Test
    fun groupJidIsNotAPersonalNumber() {
        assertEquals(null, whatsAppDigits("id:919800000001-111@g.us"))
        assertEquals("919800000001", whatsAppDigits("id:919800000001@s.whatsapp.net"))
    }
}
