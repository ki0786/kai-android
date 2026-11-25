package com.kai.assistant.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialRequestParserTest {
    @Test
    fun needTeakAndPlywood() {
        val lines = MaterialRequestParser.parse("need 10 teak and 4 plywood")
        assertEquals(listOf("Teak" to 10.0, "Plywood" to 4.0), lines.map { it.name to it.qty })
        assertTrue(lines.all { it.unit.isEmpty() })
    }

    @Test
    fun thicknessStaysOnTheNameAndTheSheetCountIsTheQuantity() {
        val lines = MaterialRequestParser.parse("18mm plywood 3 sheets")
        assertEquals(1, lines.size)
        assertEquals("18mm Plywood", lines[0].name)
        assertEquals(3.0, lines[0].qty, 0.0)
        assertEquals("sheet", lines[0].unit)
    }

    @Test
    fun fevicolWithAUnit() {
        val lines = MaterialRequestParser.parse("fevicol 2 kg")
        assertEquals("Fevicol", lines.single().name)
        assertEquals(2.0, lines.single().qty, 0.0)
        assertEquals("kg", lines.single().unit)
    }

    @Test
    fun aPriceOnTheLineIsNotPartOfTheOrder() {
        val lines = MaterialRequestParser.parse("teak 10 at 500")
        assertEquals("Teak", lines.single().name)
        assertEquals(10.0, lines.single().qty, 0.0)
        assertTrue(orderTextIsSafe(orderMessage(lines)))
        assertTrue(!orderMessage(lines).contains("500"))
        assertTrue(!orderMessage(lines).contains("₹"))
    }

    @Test
    fun commaAndNewlineLists() {
        val lines = MaterialRequestParser.parse("materials:\nfevicol 2 kg, screw 1 box")
        assertEquals(listOf("Fevicol", "Screw"), lines.map { it.name })
        assertEquals(listOf("kg", "box"), lines.map { it.unit })
    }

    @Test
    fun greetingsAndPaymentTextAreNotMaterials() {
        assertTrue(MaterialRequestParser.parse("ok").isEmpty())
        assertTrue(MaterialRequestParser.parse("thanks").isEmpty())
        assertTrue(MaterialRequestParser.parse("pay 500 to upi shop@ybl").isEmpty())
    }

    @Test
    fun sheetsOfPlywoodKeepsTheMaterialName() {
        val lines = MaterialRequestParser.parse("10 sheets of plywood")
        assertEquals("Plywood", lines.single().name)
        assertEquals(10.0, lines.single().qty, 0.0)
        assertEquals("sheet", lines.single().unit)
    }

    @Test
    fun twoNumbersWithoutAUnitAreNotGuessed() {
        assertTrue(MaterialRequestParser.parse("plywood 10 20").isEmpty())
    }
}
