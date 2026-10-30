package com.example.voice_assistant

import com.example.voice_assistant.notifications.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.TimeZone

class BillExtractorTest {
    private val parser = BillExtractor()
    private fun parse(text: String) = parser.extract(text, "2026-10-01")
    private val electricity = "Your electricity bill of ₹2,450 is due on 05-Oct-2026."
    @Test fun requiredSamplesExtractWithoutInventingMerchant() {
        val bill = parse(electricity)
        assertTrue(bill.eligible); assertNull(bill.merchant)
        assertEquals("Electricity Bill", bill.category); assertEquals(BigDecimal("2450"), bill.amount)
        assertEquals("INR", bill.currency); assertEquals("2026-10-05", bill.dueDate)
        assertEquals(BillType.BILL_PAYMENT, bill.type); assertEquals(BillPriority.HIGH, bill.priority)
        val card = parse("Your credit card payment of ₹18,500 is due on 10-Oct-2026.")
        assertTrue(card.eligible); assertEquals(BigDecimal("18500"), card.amount); assertEquals(BillPriority.MEDIUM, card.priority)
        val mobile = parse("Your mobile plan expires on 12-Oct-2026.")
        assertTrue(mobile.eligible); assertEquals(BillType.PLAN_EXPIRY, mobile.type); assertNull(mobile.amount); assertNull(mobile.currency)
    }
    @Test fun explicitMerchantAndIndianGrouping() {
        assertEquals("BESCOM", parse("BESCOM: $electricity").merchant)
        assertEquals("BESCOM", parse("Your electricity bill from BESCOM of INR 1,23,450.50 is due on 2026-10-05.").merchant)
        assertEquals(BigDecimal("123450.50"), parse("Your electricity bill of INR 1,23,450.50 is due on 2026-10-05.").amount)
    }
    @Test fun ambiguousUnsupportedAndPaidNoticesCannotSchedule() {
        listOf(electricity + " Minimum due ₹500.", electricity + " Issued 01-Oct-2026.",
            electricity.replace("05-Oct-2026", "31-Feb-2026"), electricity.replace("05-Oct-2026", "tomorrow"),
            electricity.replace("05-Oct-2026", "05/10/2026"), electricity.replace("₹2,450", "₹2,45"),
            electricity.replace("₹2,450", "USD 2450"), electricity + " Paid successfully.",
            electricity.replace("is due", "is not due"), "Your OTP is 2450.",
            electricity.replace("₹2,450", "₹0"), electricity + " Your mobile plan expires on 12-Oct-2026.")
            .forEach { assertFalse(it, parse(it).eligible) }
    }
    @Test fun staleDatesAndOversizedInputAreRejected() {
        assertFalse(parser.extract(electricity, "2026-10-06").eligible)
        try { parse("a".repeat(4001)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun planningUsesLocalTimeAndStableDedupe() {
        val bill = parse(electricity)
        val zone = TimeZone.getTimeZone("Asia/Kolkata")
        val plan = BillExtractor.plan(bill, 9, 0, zone, 0)
        assertEquals(1791171000000L, plan.dueEpochMs) // 2026-10-05 03:30 UTC
        assertEquals(plan.id, BillExtractor.plan(bill, 10, 30, zone, 0).id)
        assertNotEquals(plan.id, BillExtractor.plan(parse("BESCOM: $electricity"), 9, 0, zone, 0).id)
        try { BillExtractor.plan(bill, 9, 0, zone, plan.dueEpochMs); fail() } catch (_: IllegalArgumentException) { }
        try { BillExtractor.plan(parse("hello"), 9, 0, zone, 0); fail() } catch (_: IllegalArgumentException) { }
        try { BillExtractor.plan(bill, 24, 0, zone, 0); fail() } catch (_: IllegalArgumentException) { }
    }
}
