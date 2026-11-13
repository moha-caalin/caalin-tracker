package com.caalin.tracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import java.security.MessageDigest
import java.util.Calendar
import kotlin.math.abs

data class Parsed(
    val direction: String,
    val amount: Double,
    val name: String,
    val phone: String,
    val balance: Double?,
    val time: Long?,
)

/** Reads EVC Plus / eDahab style messages, e.g.
 *  "[-EVCPLUS-] $1 ayaad uwareejisay CRAXMAN MAXAMED CAALIN(617111194), Tar: 13/11/26 20:24:42, Haraagaagu waa $3.7899." */
object SmsParser {
    private val IN_WORDS = listOf("ka heshay", "heshay", "ka helay", "helay", "laguu soo diray", "lagu soo diray",
        "lagugu shubay", "received", "you have received")
    private val OUT_WORDS = listOf("uwareejisay", "u wareejisay", "ayaad u dirtay", "waad u dirtay", "u dirtay",
        "bixisay", "you have sent", "sent to", "paid")
    private val AMOUNT = Regex("""(?:\$|usd\s?)\s?([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val PARTY = Regex("""([A-Za-z][A-Za-z .'\-]{0,60}?)\s*\(\s*(\+?\d{6,15})\s*\)""")
    private val BALANCE = Regex("""haraag\w*\s+(?:waa\s+)?(?:\$|usd\s?)\s?([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""Tar\w*\s*:?\s*(\d{1,2})/(\d{1,2})/(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Parsed? {
        val low = text.lowercase()
        val inHit = IN_WORDS.firstOrNull { low.contains(it) }
        val outHit = if (inHit == null) OUT_WORDS.firstOrNull { low.contains(it) } else null
        val direction = when { inHit != null -> IN; outHit != null -> OUT; else -> return null }
        val word = inHit ?: outHit!!
        val amount = AMOUNT.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (amount <= 0) return null

        val after = low.indexOf(word).let { if (it >= 0) text.substring(it + word.length) else text }
        val party = PARTY.find(after) ?: PARTY.find(text)
        val name = party?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
            ?.removePrefix("ka ")?.removePrefix("Ka ") ?: ""
        val phone = party?.groupValues?.get(2) ?: ""
        val balance = BALANCE.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        val time = TIME.find(text)?.groupValues?.let { g ->
            try {
                val y = g[3].toInt().let { if (it < 100) 2000 + it else it }
                Calendar.getInstance().apply {
                    set(y, g[2].toInt() - 1, g[1].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } catch (_: Exception) { null }
        }
        return Parsed(direction, amount, name, phone, balance, time)
    }
}

object SmsStore {
    private const val WINDOW = 15 * 60_000L

    fun walletFor(text: String): Wallet? {
        val low = text.lowercase()
        return Db.wallets().firstOrNull { w -> w.smsTags().any { low.contains(it) } }
    }

    fun key(text: String): String {
        val norm = text.lowercase().replace(Regex("\\s+"), " ").trim()
        return MessageDigest.getInstance("SHA-1").digest(norm.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Saves a money message once. Returns true when something new was recorded. */
    @Synchronized
    fun ingest(text: String, receivedAt: Long): Boolean {
        val w = walletFor(text) ?: return false
        val p = SmsParser.parse(text) ?: return false
        val key = key(text)
        if (Db.hasSmsKey(key)) return false
        val time = p.time ?: receivedAt
        val phone9 = last9(p.phone)

        // A send started in the app that is still waiting: this message confirms it.
        if (p.direction == OUT && Pending.active && Pending.walletId == w.id && abs(Pending.amount - p.amount) < 0.001 &&
            (phone9.isEmpty() || last9(Pending.phone) == phone9)
        ) {
            Pending.transcript.append(text).append("\n")
            Pending.finish(Status.COMPLETED, skipEnrich = true)
        }

        val candidates = Db.near(w.id, p.direction, p.amount, time - WINDOW, time + WINDOW)
        fun samePerson(t: Tx) = phone9.isEmpty() || t.phone.isEmpty() || last9(t.phone) == phone9

        // Attach to the in-app send it belongs to.
        val app = candidates.firstOrNull { it.source == "APP" && it.smsKey == null && samePerson(it) }
        if (app != null) {
            Db.updateTx(app.copy(
                categoryId = app.categoryId ?: Db.guessCategory(p.phone, p.direction),
                status = Status.COMPLETED, name = p.name.ifBlank { app.name }, phone = app.phone.ifBlank { p.phone },
                balance = p.balance ?: app.balance, smsKey = key, response = (app.response + "\n\n" + text).trim(),
            ))
            return true
        }
        // Same payment already recorded from another copy of the message (SMS vs. pop-up).
        if (candidates.any { it.smsKey != null && samePerson(it) && abs(it.createdAt - time) < 3 * 60_000L }) return false

        Db.addTx(Tx(
            walletId = w.id, walletName = w.name, direction = p.direction, phone = p.phone, name = p.name,
            amount = p.amount, status = Status.COMPLETED, response = text, createdAt = time, source = "SMS",
            balance = p.balance, smsKey = key, categoryId = Db.guessCategory(p.phone, p.direction),
        ))
        return true
    }

    fun canRead(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Reads money messages already in the inbox. Returns how many new ones were saved. */
    fun importInbox(ctx: Context, days: Int = 90): Int {
        if (!canRead(ctx)) return 0
        val since = System.currentTimeMillis() - days * 24L * 3600_000L
        var added = 0
        try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()),
                "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val body = c.getString(0) ?: continue
                    if (ingest(body, c.getLong(1))) added++
                }
            }
        } catch (_: Exception) {
        }
        return added
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Db.init(context)
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        SmsStore.ingest(body, msgs[0].timestampMillis)
    }
}
