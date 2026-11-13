package com.caalin.tracker

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
) {
    val hasPin get() = !pinEnc.isNullOrEmpty() && !pinIv.isNullOrEmpty()

    fun ruleList(): List<Pair<String, String>> = rules.lines().mapNotNull { line ->
        val parts = line.split("=>")
        if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim().lowercase() to parts[1].trim() else null
    }

    fun hasPinRule() = ruleList().any { it.second.contains("{PIN}") }
    fun successList() = keywords(successKw)
    fun failList() = keywords(failKw)
}

fun keywords(s: String) = s.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

data class Tx(
    val id: Long,
    val walletId: Long,
    val walletName: String,
    val phone: String,
    val amount: Double,
    val status: String,
    val response: String,
    val createdAt: Long,
)

object Status {
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val UNKNOWN = "UNKNOWN"
}

object Defaults {
    const val PIN_RULES = "pin => {PIN}\nhaa => 1"
    const val SUCCESS = "u wareejisay, ayaad u dirtay, waad u dirtay, waxaad u dirtay, la diray, guul, success, successful, sent"
    const val FAIL = "khalad, qalad, ma haysatid, kuguma filna, kugu filnayn, ma guulaysan, guuldarr, failed, error, invalid, incorrect, wrong, insufficient"
}

class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "caalin.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE wallets(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, color INTEGER NOT NULL, " +
                "template TEXT NOT NULL, sim INTEGER NOT NULL DEFAULT 0, pin_enc TEXT, pin_iv TEXT, " +
                "rules TEXT NOT NULL, success_kw TEXT NOT NULL, fail_kw TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE txs(id INTEGER PRIMARY KEY AUTOINCREMENT, wallet_id INTEGER, wallet_name TEXT, phone TEXT, " +
                "amount REAL, status TEXT, response TEXT, created_at INTEGER)"
        )
        fun seed(name: String, color: Long, template: String) {
            db.insert("wallets", null, ContentValues().apply {
                put("name", name); put("color", color.toInt()); put("template", template); put("sim", 0)
                put("rules", Defaults.PIN_RULES); put("success_kw", Defaults.SUCCESS); put("fail_kw", Defaults.FAIL)
            })
        }
        seed("EVC Plus", 0xFF1E8E3E, "*712*{NUMBER}*{AMOUNT}#")
        seed("eDahab", 0xFF1565C0, "")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

object Db {
    private lateinit var h: DbHelper

    fun init(ctx: Context) {
        h = DbHelper(ctx.applicationContext)
    }

    private fun Cursor.s(col: String): String? = getString(getColumnIndexOrThrow(col))
    private fun Cursor.l(col: String): Long = getLong(getColumnIndexOrThrow(col))

    private fun wallet(c: Cursor) = Wallet(
        id = c.l("id"), name = c.s("name") ?: "", color = c.l("color").toInt(), template = c.s("template") ?: "",
        sim = c.l("sim").toInt(), pinEnc = c.s("pin_enc"), pinIv = c.s("pin_iv"), rules = c.s("rules") ?: "",
        successKw = c.s("success_kw") ?: "", failKw = c.s("fail_kw") ?: "",
    )

    private fun tx(c: Cursor) = Tx(
        id = c.l("id"), walletId = c.l("wallet_id"), walletName = c.s("wallet_name") ?: "", phone = c.s("phone") ?: "",
        amount = c.getDouble(c.getColumnIndexOrThrow("amount")), status = c.s("status") ?: "",
        response = c.s("response") ?: "", createdAt = c.l("created_at"),
    )

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
            put("rules", w.rules); put("success_kw", w.successKw); put("fail_kw", w.failKw)
        }
        return if (w.id > 0) {
            h.writableDatabase.update("wallets", cv, "id=?", arrayOf(w.id.toString())); w.id
        } else h.writableDatabase.insert("wallets", null, cv)
    }

    fun setPin(id: Long, enc: String?, iv: String?) {
        h.writableDatabase.update("wallets", ContentValues().apply { put("pin_enc", enc); put("pin_iv", iv) },
            "id=?", arrayOf(id.toString()))
    }

    fun clearAllPins() {
        h.writableDatabase.execSQL("UPDATE wallets SET pin_enc=NULL, pin_iv=NULL")
    }

    fun deleteWallet(id: Long) {
        h.writableDatabase.delete("wallets", "id=?", arrayOf(id.toString()))
    }

    fun addTx(t: Tx) {
        h.writableDatabase.insert("txs", null, ContentValues().apply {
            put("wallet_id", t.walletId); put("wallet_name", t.walletName); put("phone", t.phone)
            put("amount", t.amount); put("status", t.status); put("response", t.response); put("created_at", t.createdAt)
        })
    }

    fun txs(walletId: Long? = null, limit: Int = 500): List<Tx> {
        val sql = if (walletId == null) "SELECT * FROM txs ORDER BY created_at DESC LIMIT $limit"
        else "SELECT * FROM txs WHERE wallet_id=$walletId ORDER BY created_at DESC LIMIT $limit"
        return h.readableDatabase.rawQuery(sql, null).use { c ->
            val out = ArrayList<Tx>(); while (c.moveToNext()) out.add(tx(c)); out
        }
    }

    fun deleteTx(id: Long) {
        h.writableDatabase.delete("txs", "id=?", arrayOf(id.toString()))
    }

    fun total(walletId: Long, since: Long): Double =
        h.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount),0) FROM txs WHERE wallet_id=? AND status='COMPLETED' AND created_at>=?",
            arrayOf(walletId.toString(), since.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
}
