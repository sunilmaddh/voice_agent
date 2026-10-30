package com.example.voice_assistant.notifications

import java.math.BigDecimal
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.*

enum class BillType { BILL_PAYMENT, PLAN_EXPIRY }
enum class BillPriority { HIGH, MEDIUM }
data class BillCandidate(val category: String?, val merchant: String?, val amount: BigDecimal?,
    val currency: String?, val dueDate: String?, val type: BillType?, val priority: BillPriority?,
    val issues: List<String>) {
    val eligible get() = issues.isEmpty() && category != null && dueDate != null && type != null
    fun toWire() = mapOf("category" to category, "merchant" to merchant, "amount" to amount?.toDouble(),
        "currency" to currency, "due_date" to dueDate, "type" to type?.name, "priority" to priority?.name,
        "issues" to issues, "canCreateReminder" to eligible,
        "priorityRule" to "HIGH when due within 7 days; otherwise MEDIUM. Not a sender-provided priority.")
}

/** Conservative English POC extraction. Source content never becomes an executable command. */
class BillExtractor {
    fun extract(input: String, today: String): BillCandidate {
        require(input.length in 1..4000) { "BILL_INPUT_LENGTH" }
        val text = input.replace('\u2019', '\'').replace(Regex("\\s+"), " ").trim()
        val issues = mutableListOf<String>()
        val categories = listOf("Electricity Bill" to Regex("\\belectricity bill\\b", RegexOption.IGNORE_CASE),
            "Credit Card Payment" to Regex("\\bcredit card (?:payment|bill)\\b", RegexOption.IGNORE_CASE),
            "Mobile Plan" to Regex("\\bmobile plan\\b", RegexOption.IGNORE_CASE))
            .filter { it.second.containsMatchIn(text) }.map { it.first }
        val category = categories.singleOrNull()
        if (category == null) issues += "UNSUPPORTED_OR_AMBIGUOUS_CATEGORY"
        val type = category?.let { if (it == "Mobile Plan") BillType.PLAN_EXPIRY else BillType.BILL_PAYMENT }
        if (Regex("\\b(paid|received|successful|cancelled|canceled|refunded|not due|no payment|overdue)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
            issues += "NOT_A_CLEAR_FUTURE_DUE_NOTICE"
        val cues = Regex(if (type == BillType.PLAN_EXPIRY) "\\bexpires(?: on)? " else "\\bdue(?: on| by)? ", RegexOption.IGNORE_CASE)
        val dateTokens = Regex("\\b(?:\\d{2}-[A-Za-z]{3}-\\d{4}|\\d{4}-\\d{2}-\\d{2})\\b").findAll(text).toList()
        val cue = cues.findAll(text).toList().singleOrNull()
        val token = dateTokens.singleOrNull()
        val due = token?.takeIf { cue != null && it.range.first == cue.range.last + 1 }?.value?.let { parseDate(it) }
        if (due == null) issues += "MISSING_INVALID_OR_AMBIGUOUS_DUE_DATE"
        if (due != null && due < today) issues += "DUE_DATE_IN_PAST"
        val money = Regex("(?:₹|\\bINR\\b|\\bRs\\.?)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?![0-9.])", RegexOption.IGNORE_CASE).findAll(text).toList()
        val raw = money.singleOrNull()?.groupValues?.get(1)
        val grouping = raw != null && Regex("(?:[0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+|[1-9][0-9]?(?:,[0-9]{2})*,[0-9]{3})(?:\\.[0-9]{1,2})?").matches(raw)
        val amount = if (grouping) raw.replace(",", "").toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal("1000000000") } else null
        if (money.size > 1 || (money.isNotEmpty() && amount == null)) issues += "AMBIGUOUS_OR_INVALID_AMOUNT"
        if (type == BillType.BILL_PAYMENT && amount == null) issues += "MISSING_PAYMENT_AMOUNT"
        if (Regex("[$€£]|\\b(?:USD|EUR|GBP)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) issues += "UNSUPPORTED_OR_MIXED_CURRENCY"
        // Merchant is only extracted from explicit, bounded attribution; never inferred from category or package.
        val merchant = Regex("^([A-Za-z][A-Za-z0-9 &.-]{1,49}):").find(text)?.groupValues?.get(1)
            ?: Regex("\\bfrom ([A-Za-z][A-Za-z0-9 &.-]{1,49}?) (?=(?:is |of |for |due |expires ))", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        val priority = due?.let { if (utc(it) - utc(today) <= 7 * 86400000L) BillPriority.HIGH else BillPriority.MEDIUM }
        return BillCandidate(category, merchant, amount, amount?.let { "INR" }, due, type, priority, issues.distinct())
    }
    companion object {
        fun today(zone: TimeZone = TimeZone.getDefault(), now: Long = System.currentTimeMillis()) =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(Date(now))
        private fun parseDate(raw: String): String? {
            val pattern = if (raw.matches(Regex("\\d{4}-.*"))) "yyyy-MM-dd" else "dd-MMM-yyyy"
            val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
            val position = ParsePosition(0)
            val parsed = format.parse(raw, position) ?: return null
            if (position.index != raw.length) return null
            return today(TimeZone.getTimeZone("UTC"), parsed.time)
        }
        private fun utc(date: String) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(date)!!.time
        fun plan(bill: BillCandidate, hour: Int, minute: Int, zone: TimeZone, now: Long): BillReminderPlan {
            require(bill.eligible && hour in 0..23 && minute in 0..59) { "BILL_NOT_ELIGIBLE" }
            val date = bill.dueDate!!.split('-').map { it.toInt() }
            val calendar = Calendar.getInstance(zone).apply { clear(); isLenient = false; set(date[0], date[1]-1, date[2], hour, minute, 0) }
            val due = calendar.timeInMillis
            require(due > now) { "BILL_REMINDER_TIME_IN_PAST" }
            val title = listOfNotNull(bill.category, bill.merchant, bill.amount?.let { "INR ${it.toPlainString()}" }, "due ${bill.dueDate}").joinToString(" — ")
            // Same extracted bill, irrespective of delivery channel or selected time, has one pending reminder.
            val key = listOf(bill.category, bill.merchant?.lowercase(Locale.US), bill.amount?.stripTrailingZeros()?.toPlainString(), bill.currency, bill.dueDate, bill.type?.name).joinToString("|")
            val id = "bill-" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return BillReminderPlan(id, title, due, zone.id)
        }
    }
}
data class BillReminderPlan(val id: String, val title: String, val dueEpochMs: Long, val zone: String)
