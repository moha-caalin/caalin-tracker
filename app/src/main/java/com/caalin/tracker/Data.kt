package com.caalin.tracker

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

const val IN = "IN"
const val OUT = "OUT"

data class Wallet(
    val id: Long,
    val name: String,
    val color: Int,
    val template: String,
    val sim: Int,
    val pinEnc: String?,
    val pinIv: String?,
    val rules: String,
    val successKw: String,
    val failKw: String,
    val smsTag: String,
) {
    val hasPin get() = !pinEnc.isNullOrEmpty() && !pinIv.isNullOrEmpty()

    fun ruleList(): List<Pair<String, String>> = rules.lines().mapNotNull { line ->
        val parts = line.split("=>")
        if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim().lowercase() to parts[1].trim() else null
    }

    fun hasPinRule() = ruleList().any { it.second.contains("{PIN}") }
    fun successList() = keywords(successKw)
    fun failList() = keywords(failKw)
    fun smsTags() = keywords(smsTag)
}

fun keywords(s: String) = s.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
fun last9(p: String) = p.filter { it.isDigit() }.takeLast(9)

data class Tx(
    val id: Long = 0,
    val walletId: Long,
    val walletName: String,
    val direction: String,
    val phone: String,
    val name: String,
    val amount: Double,
    val status: String,
    val response: String,
    val createdAt: Long,
    val source: String,
    val balance: Double? = null,
    val smsKey: String? = null,
) {
    val who get() = name.ifBlank { phone.ifBlank { walletName } }
}

object Status {
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val UNKNOWN = "UNKNOWN"
}

object Defaults {
    const val PIN_RULES = "pin => {PIN}\nhaa => 1"
    const val SUCCESS = "uwareejisay, u wareejisay, ayaad u dirtay, waad u dirtay, waxaad u dirtay, haraagaagu, success, successful, sent"
    const val FAIL = "khalad, qalad, ma haysatid, kuguma filna, kugu filnayn, ma guulaysan, guuldarr, failed, error, invalid, not valid, incorrect, wrong, insufficient"
}

class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "caalin.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE wallets(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, color INTEGER NOT NULL, " +
                "template TEXT NOT NULL, sim INTEGER NOT NULL DEFAULT 0, pin_enc TEXT, pin_iv TEXT, " +
                "rules TEXT NOT NULL, success_kw TEXT NOT NULL, fail_kw TEXT NOT NULL, sms_tag TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE TABLE txs(id INTEGER PRIMARY KEY AUTOINCREMENT, wallet_id INTEGER, wallet_name TEXT, phone TEXT, " +
                "amount REAL, status TEXT, response TEXT, created_at INTEGER, direction TEXT NOT NULL DEFAULT 'OUT', " +
                "name TEXT, source TEXT NOT NULL DEFAULT 'APP', balance REAL, sms_key TEXT)"
        )
        db.execSQL("CREATE INDEX idx_txs_key ON txs(sms_key)")
        fun seed(name: String, color: Long, template: String, tag: String) {
            db.insert("wallets", null, ContentValues().apply {
                put("name", name); put("color", color.toInt()); put("template", template); put("sim", 0)
                put("rules", Defaults.PIN_RULES); put("success_kw", Defaults.SUCCESS); put("fail_kw", Defaults.FAIL)
                put("sms_tag", tag)
            })
        }
        seed("EVC Plus", 0xFF1E8E3E, "*712*{NUMBER}*{AMOUNT}#", "evcplus, evc plus")
        seed("eDahab", 0xFF1565C0, "", "edahab, e-dahab")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE txs ADD COLUMN direction TEXT NOT NULL DEFAULT 'OUT'")
            db.execSQL("ALTER TABLE txs ADD COLUMN name TEXT")
            db.execSQL("ALTER TABLE txs ADD COLUMN source TEXT NOT NULL DEFAULT 'APP'")
            db.execSQL("ALTER TABLE txs ADD COLUMN balance REAL")
            db.execSQL("ALTER TABLE txs ADD COLUMN sms_key TEXT")
            db.execSQL("CREATE INDEX idx_txs_key ON txs(sms_key)")
            db.execSQL("ALTER TABLE wallets ADD COLUMN sms_tag TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE wallets SET sms_tag='evcplus, evc plus' WHERE lower(name) LIKE 'evc%'")
            db.execSQL("UPDATE wallets SET sms_tag='edahab, e-dahab' WHERE lower(name) LIKE '%dahab%'")
            db.execSQL("UPDATE wallets SET success_kw='uwareejisay, haraagaagu, ' || success_kw")
            db.execSQL("UPDATE wallets SET fail_kw=fail_kw || ', not valid'")
        }
    }
}

object Db {
    private lateinit var h: DbHelper

    fun init(ctx: Context) {
        if (!::h.isInitialized) h = DbHelper(ctx.applicationContext)
    }

    private fun Cursor.s(col: String): String? = getString(getColumnIndexOrThrow(col))
    private fun Cursor.l(col: String): Long = getLong(getColumnIndexOrThrow(col))

    private fun wallet(c: Cursor) = Wallet(
        id = c.l("id"), name = c.s("name") ?: "", color = c.l("color").toInt(), template = c.s("template") ?: "",
        sim = c.l("sim").toInt(), pinEnc = c.s("pin_enc"), pinIv = c.s("pin_iv"), rules = c.s("rules") ?: "",
        successKw = c.s("success_kw") ?: "", failKw = c.s("fail_kw") ?: "", smsTag = c.s("sms_tag") ?: "",
    )

    private fun tx(c: Cursor): Tx {
        val bi = c.getColumnIndexOrThrow("balance")
        return Tx(
            id = c.l("id"), walletId = c.l("wallet_id"), walletName = c.s("wallet_name") ?: "",
            direction = c.s("direction") ?: OUT, phone = c.s("phone") ?: "", name = c.s("name") ?: "",
            amount = c.getDouble(c.getColumnIndexOrThrow("amount")), status = c.s("status") ?: "",
            response = c.s("response") ?: "", createdAt = c.l("created_at"), source = c.s("source") ?: "APP",
            balance = if (c.isNull(bi)) null else c.getDouble(bi), smsKey = c.s("sms_key"),
        )
    }

    private fun txList(sql: String, args: Array<String>): List<Tx> =
        h.readableDatabase.rawQuery(sql, args).use { c -> val out = ArrayList<Tx>(); while (c.moveToNext()) out.add(tx(c)); out }

    fun wallets(): List<Wallet> =
        h.readableDatabase.rawQuery("SELECT * FROM wallets ORDER BY id", null).use { c ->
            val out = ArrayList<Wallet>(); while (c.moveToNext()) out.add(wallet(c)); out
        }

    fun wallet(id: Long): Wallet? =
        h.readableDatabase.rawQuery("SELECT * FROM wallets WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) wallet(c) else null
        }

    /** Saves everything except the PIN. Returns the wallet id. */
    fun saveWallet(w: Wallet): Long {
        val cv = ContentValues().apply {
            put("name", w.name); put("color", w.color); put("template", w.template); put("sim", w.sim)
            put("rules", w.rules); put("success_kw", w.successKw); put("fail_kw", w.failKw); put("sms_tag", w.smsTag)
        }
        return if (w.id > 0) {
            h.writableDatabase.update("wallets", cv, "id=?", arrayOf(w.id.toString())); w.id
        } else h.writableDatabase.insert("wallets", null, cv)
    }

    fun setPin(id: Long, enc: String?, iv: String?) {
        h.writableDatabase.update("wallets", ContentValues().apply { put("pin_enc", enc); put("pin_iv", iv) },
            "id=?", arrayOf(id.toString()))
    }

    fun clearAllPins() = h.writableDatabase.execSQL("UPDATE wallets SET pin_enc=NULL, pin_iv=NULL")

    fun deleteWallet(id: Long) {
        h.writableDatabase.delete("wallets", "id=?", arrayOf(id.toString()))
    }

    private fun cv(t: Tx) = ContentValues().apply {
        put("wallet_id", t.walletId); put("wallet_name", t.walletName); put("direction", t.direction)
        put("phone", t.phone); put("name", t.name); put("amount", t.amount); put("status", t.status)
        put("response", t.response); put("created_at", t.createdAt); put("source", t.source)
        if (t.balance == null) putNull("balance") else put("balance", t.balance)
        if (t.smsKey == null) putNull("sms_key") else put("sms_key", t.smsKey)
    }

    fun addTx(t: Tx): Long = h.writableDatabase.insert("txs", null, cv(t))

    fun updateTx(t: Tx) {
        h.writableDatabase.update("txs", cv(t), "id=?", arrayOf(t.id.toString()))
    }

    fun deleteTx(id: Long) {
        h.writableDatabase.delete("txs", "id=?", arrayOf(id.toString()))
    }

    fun txs(walletId: Long? = null, direction: String? = null, limit: Int = 500): List<Tx> {
        val where = ArrayList<String>(); val args = ArrayList<String>()
        if (walletId != null) { where.add("wallet_id=?"); args.add(walletId.toString()) }
        if (direction != null) { where.add("direction=?"); args.add(direction) }
        val w = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        return txList("SELECT * FROM txs $w ORDER BY created_at DESC LIMIT $limit", args.toTypedArray())
    }

    /** Completed totals since a time: [received, sent]. */
    fun totals(walletId: Long?, since: Long): DoubleArray {
        val out = doubleArrayOf(0.0, 0.0)
        val extra = if (walletId != null) " AND wallet_id=$walletId" else ""
        h.readableDatabase.rawQuery(
            "SELECT direction, COALESCE(SUM(amount),0) FROM txs WHERE status='COMPLETED' AND created_at>=?$extra GROUP BY direction",
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == IN) out[0] = c.getDouble(1) else out[1] = c.getDouble(1)
            }
        }
        return out
    }

    fun latestBalance(walletId: Long): Double? =
        h.readableDatabase.rawQuery(
            "SELECT balance FROM txs WHERE wallet_id=? AND balance IS NOT NULL ORDER BY created_at DESC, id DESC LIMIT 1",
            arrayOf(walletId.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else null }

    fun hasSmsKey(key: String): Boolean =
        h.readableDatabase.rawQuery("SELECT 1 FROM txs WHERE sms_key=? LIMIT 1", arrayOf(key)).use { it.moveToFirst() }

    /** Transactions of one wallet/direction/amount around a time, optionally only from one source. */
    fun near(walletId: Long, direction: String, amount: Double, from: Long, to: Long, source: String? = null): List<Tx> {
        val src = if (source != null) " AND source='$source'" else ""
        return txList(
            "SELECT * FROM txs WHERE wallet_id=? AND direction=? AND ABS(amount-?)<0.001 AND created_at BETWEEN ? AND ?$src ORDER BY created_at DESC",
            arrayOf(walletId.toString(), direction, amount.toString(), from.toString(), to.toString())
        )
    }

    fun recentRecipients(walletId: Long): List<Pair<String, String>> =
        h.readableDatabase.rawQuery(
            "SELECT phone, MAX(COALESCE(name,'')) n, MAX(created_at) m FROM txs WHERE wallet_id=? AND direction='OUT' " +
                "AND phone<>'' GROUP BY phone ORDER BY m DESC LIMIT 6",
            arrayOf(walletId.toString())
        ).use { c -> val out = ArrayList<Pair<String, String>>(); while (c.moveToNext()) out.add(c.getString(0) to (c.getString(1) ?: "")); out }
}
