package com.caalin.tracker

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
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
    val categoryId: Long? = null,
    val note: String = "",
    val orderId: Long? = null,
) {
    val counted get() = createdAt >= Prefs.countFrom
    val who get() = name.ifBlank { phone.ifBlank { walletName } }
}

object Status {
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val UNKNOWN = "UNKNOWN"
}

data class Category(val id: Long, val name: String, val direction: String, val color: Int)

data class Task(val id: Long = 0, val title: String, val done: Boolean = false, val due: Long? = null, val created: Long = System.currentTimeMillis())

object OrderStatus {
    val NAMES = listOf("New", "Ordered", "Shipped", "Partly arrived", "Arrived", "Closed")
    const val CLOSED = 5
}

/** kind: BUY = purchase from a supplier (e.g. China), SELL = a customer's order. */
data class Order(
    val id: Long = 0, val kind: String, val party: String, val phone: String, val title: String, val status: Int = 0,
    val paidManual: Double = 0.0, val note: String = "", val created: Long = System.currentTimeMillis(), val due: Long? = null,
) { companion object { const val BUY = "BUY"; const val SELL = "SELL" } }

data class OrderItem(val id: Long = 0, val orderId: Long, val name: String, val qty: Double, val price: Double, val received: Double = 0.0)
data class Shipment(val id: Long = 0, val orderId: Long, val label: String, val date: Long, val freight: Double, val arrived: Boolean, val note: String = "")

/** Money summary of one order. */
data class OrderMoney(val items: Double, val freight: Double, val paid: Double) {
    val total get() = items + freight
    val left get() = (total - paid).coerceAtLeast(0.0)
}

data class Note(val id: Long = 0, val title: String, val body: String, val pinned: Boolean, val phone: String, val updated: Long)

/** direction: THEY = they owe me, ME = I owe them. */
data class Debt(
    val id: Long = 0, val name: String, val phone: String, val direction: String, val amount: Double, val paid: Double = 0.0,
    val due: Long? = null, val note: String = "", val created: Long = System.currentTimeMillis(), val closed: Boolean = false,
    val history: String = "",
) {
    val left get() = (amount - paid).coerceAtLeast(0.0)
    companion object { const val THEY = "THEY"; const val ME = "ME" }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { if (!::sp.isInitialized) sp = ctx.getSharedPreferences("caalin", Context.MODE_PRIVATE) }

    /** When counting starts; -1 until the user has chosen. Older payments are kept but not counted. */
    var start: Long
        get() = sp.getLong("start", -1L)
        set(v) { sp.edit().putLong("start", v).apply() }
    val countFrom: Long get() = if (start < 0) 0L else start

    /** How long the app may stay in the background before the fingerprint is asked again. */
    var lockAfterSec: Int
        get() = sp.getInt("lock_after", 60)
        set(v) { sp.edit().putInt("lock_after", v).apply() }

    fun opening(walletId: Long): Double? =
        if (sp.contains("open_$walletId")) sp.getFloat("open_$walletId", 0f).toDouble() else null
    fun setOpening(walletId: Long, v: Double?) {
        if (v == null) sp.edit().remove("open_$walletId").apply() else sp.edit().putFloat("open_$walletId", v.toFloat()).apply()
    }
}

object Defaults {
    const val PIN_RULES = "pin => {PIN}\nhaa => 1"
    const val SUCCESS = "uwareejisay, u wareejisay, ayaad u dirtay, waad u dirtay, waxaad u dirtay, haraagaagu, success, successful, sent"
    const val FAIL = "khalad, qalad, ma haysatid, kuguma filna, kugu filnayn, ma guulaysan, guuldarr, failed, error, invalid, not valid, incorrect, wrong, insufficient"
}

class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "caalin.db", null, 5) {
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
        v3(db)
        v4(db)
        v5(db)
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
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE txs ADD COLUMN category_id INTEGER")
            db.execSQL("ALTER TABLE txs ADD COLUMN note TEXT")
            v3(db, tablesOnly = true)
        }
        if (oldVersion < 4) v4(db)
        if (oldVersion < 5) v5(db)
    }

    private fun v5(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE txs ADD COLUMN order_id INTEGER")
        db.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, done INTEGER NOT NULL DEFAULT 0, due INTEGER, created INTEGER, done_at INTEGER)")
        db.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, party TEXT, phone TEXT, title TEXT, status INTEGER NOT NULL DEFAULT 0, " +
            "paid_manual REAL NOT NULL DEFAULT 0, note TEXT, created INTEGER, due INTEGER)")
        db.execSQL("CREATE TABLE order_items(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, name TEXT, qty REAL, price REAL, received REAL NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE shipments(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, label TEXT, date INTEGER, freight REAL NOT NULL DEFAULT 0, arrived INTEGER NOT NULL DEFAULT 0, note TEXT)")
    }

    private fun v4(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, body TEXT, pinned INTEGER NOT NULL DEFAULT 0, " +
            "phone TEXT, updated INTEGER)")
        db.execSQL("CREATE TABLE debts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, phone TEXT, direction TEXT, amount REAL, " +
            "paid REAL NOT NULL DEFAULT 0, due INTEGER, note TEXT, created INTEGER, closed INTEGER NOT NULL DEFAULT 0, history TEXT)")
    }

    private fun v3(db: SQLiteDatabase, tablesOnly: Boolean = false) {
        if (!tablesOnly) {
            // fresh install: txs already has the columns? add them here
            db.execSQL("ALTER TABLE txs ADD COLUMN category_id INTEGER")
            db.execSQL("ALTER TABLE txs ADD COLUMN note TEXT")
        }
        db.execSQL("CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, direction TEXT NOT NULL, color INTEGER NOT NULL)")
        val outs = listOf("Stock purchase", "Freight / Shipping", "Rent", "Salary", "Airtime", "Personal", "Other")
        val ins = listOf("Customer payment", "Loan returned", "Other")
        var i = 0
        fun add(n: String, d: String) {
            db.insert("categories", null, ContentValues().apply {
                put("name", n); put("direction", d); put("color", PALETTE[i % PALETTE.size]); i++
            })
        }
        outs.forEach { add(it, OUT) }
        ins.forEach { add(it, IN) }
    }
}

object Db {
    private lateinit var h: DbHelper

    fun init(ctx: Context) {
        if (!::h.isInitialized) h = DbHelper(ctx.applicationContext)
        Prefs.init(ctx.applicationContext)
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
            categoryId = c.getColumnIndexOrThrow("category_id").let { if (c.isNull(it)) null else c.getLong(it) },
            note = c.s("note") ?: "",
            orderId = c.getColumnIndexOrThrow("order_id").let { if (c.isNull(it)) null else c.getLong(it) },
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
        if (t.categoryId == null) putNull("category_id") else put("category_id", t.categoryId)
        put("note", t.note)
        if (t.orderId == null) putNull("order_id") else put("order_id", t.orderId)
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
            arrayOf(maxOf(since, Prefs.countFrom).toString())
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

    fun tx(id: Long): Tx? = txList("SELECT * FROM txs WHERE id=?", arrayOf(id.toString())).firstOrNull()

    fun txsBetween(from: Long, to: Long): List<Tx> =
        txList("SELECT * FROM txs WHERE created_at BETWEEN ? AND ? ORDER BY created_at DESC", arrayOf(from.toString(), to.toString()))

    // ----- categories -----
    private fun cat(c: Cursor) = Category(c.l("id"), c.s("name") ?: "", c.s("direction") ?: OUT, c.l("color").toInt())

    fun categories(direction: String? = null): List<Category> {
        val sql = if (direction == null) "SELECT * FROM categories ORDER BY direction DESC, id" else "SELECT * FROM categories WHERE direction=? ORDER BY id"
        return h.readableDatabase.rawQuery(sql, if (direction == null) null else arrayOf(direction)).use { c ->
            val out = ArrayList<Category>(); while (c.moveToNext()) out.add(cat(c)); out
        }
    }

    fun categoryMap(): Map<Long, Category> = categories().associateBy { it.id }

    fun addCategory(name: String, direction: String): Long {
        val n = categories().size
        return h.writableDatabase.insert("categories", null, ContentValues().apply {
            put("name", name); put("direction", direction); put("color", PALETTE[n % PALETTE.size])
        })
    }

    fun renameCategory(id: Long, name: String) {
        h.writableDatabase.update("categories", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
    }

    fun deleteCategory(id: Long) {
        h.writableDatabase.execSQL("UPDATE txs SET category_id=NULL WHERE category_id=?", arrayOf<Any>(id))
        h.writableDatabase.delete("categories", "id=?", arrayOf(id.toString()))
    }

    fun setCategory(txId: Long, catId: Long?) {
        h.writableDatabase.update("txs", ContentValues().apply { if (catId == null) putNull("category_id") else put("category_id", catId) },
            "id=?", arrayOf(txId.toString()))
    }

    fun setNote(txId: Long, note: String) {
        h.writableDatabase.update("txs", ContentValues().apply { put("note", note) }, "id=?", arrayOf(txId.toString()))
    }

    /** The category last used for this person in this direction. */
    fun guessCategory(phone: String, direction: String): Long? {
        val p = last9(phone)
        if (p.isEmpty()) return null
        return h.readableDatabase.rawQuery(
            "SELECT phone, category_id FROM txs WHERE direction=? AND category_id IS NOT NULL ORDER BY created_at DESC LIMIT 500",
            arrayOf(direction)
        ).use { c ->
            var found: Long? = null
            while (found == null && c.moveToNext()) if (last9(c.getString(0) ?: "") == p) found = c.getLong(1)
            found
        }
    }

    /** Counted, completed payments still without a category. */
    fun uncategorized(): List<Tx> = txList(
        "SELECT * FROM txs WHERE status='COMPLETED' AND category_id IS NULL AND created_at>=? ORDER BY created_at DESC",
        arrayOf(Prefs.countFrom.toString())
    )

    /** Opening balance of a wallet at the counting start: saved value, or worked out from SMS balances. */
    fun opening(walletId: Long): Double? {
        Prefs.opening(walletId)?.let { return it }
        val start = Prefs.countFrom.toString()
        h.readableDatabase.rawQuery(
            "SELECT balance FROM txs WHERE wallet_id=? AND balance IS NOT NULL AND created_at<? ORDER BY created_at DESC, id DESC LIMIT 1",
            arrayOf(walletId.toString(), start)
        ).use { if (it.moveToFirst()) return it.getDouble(0) }
        h.readableDatabase.rawQuery(
            "SELECT balance, amount, direction FROM txs WHERE wallet_id=? AND balance IS NOT NULL AND created_at>=? ORDER BY created_at ASC, id ASC LIMIT 1",
            arrayOf(walletId.toString(), start)
        ).use {
            if (it.moveToFirst()) {
                val b = it.getDouble(0); val a = it.getDouble(1)
                return if (it.getString(2) == IN) b - a else b + a
            }
        }
        return null
    }

    // ----- notes -----
    fun notes(): List<Note> = h.readableDatabase.rawQuery("SELECT * FROM notes ORDER BY pinned DESC, updated DESC", null).use { c ->
        val out = ArrayList<Note>()
        while (c.moveToNext()) out.add(Note(c.l("id"), c.s("title") ?: "", c.s("body") ?: "", c.l("pinned") == 1L, c.s("phone") ?: "", c.l("updated")))
        out
    }

    fun saveNote(n: Note): Long {
        val cv = ContentValues().apply {
            put("title", n.title); put("body", n.body); put("pinned", if (n.pinned) 1 else 0); put("phone", n.phone)
            put("updated", System.currentTimeMillis())
        }
        return if (n.id > 0) { h.writableDatabase.update("notes", cv, "id=?", arrayOf(n.id.toString())); n.id }
        else h.writableDatabase.insert("notes", null, cv)
    }

    fun deleteNote(id: Long) { h.writableDatabase.delete("notes", "id=?", arrayOf(id.toString())) }

    // ----- debts -----
    private fun debt(c: Cursor) = Debt(
        c.l("id"), c.s("name") ?: "", c.s("phone") ?: "", c.s("direction") ?: Debt.THEY,
        c.getDouble(c.getColumnIndexOrThrow("amount")), c.getDouble(c.getColumnIndexOrThrow("paid")),
        c.getColumnIndexOrThrow("due").let { if (c.isNull(it)) null else c.getLong(it) }, c.s("note") ?: "",
        c.l("created"), c.l("closed") == 1L, c.s("history") ?: "",
    )

    fun debts(): List<Debt> = h.readableDatabase.rawQuery("SELECT * FROM debts ORDER BY closed ASC, COALESCE(due, created) ASC", null).use { c ->
        val out = ArrayList<Debt>(); while (c.moveToNext()) out.add(debt(c)); out
    }

    fun saveDebt(d: Debt): Long {
        val cv = ContentValues().apply {
            put("name", d.name); put("phone", d.phone); put("direction", d.direction); put("amount", d.amount); put("paid", d.paid)
            if (d.due == null) putNull("due") else put("due", d.due)
            put("note", d.note); put("created", d.created); put("closed", if (d.closed) 1 else 0); put("history", d.history)
        }
        return if (d.id > 0) { h.writableDatabase.update("debts", cv, "id=?", arrayOf(d.id.toString())); d.id }
        else h.writableDatabase.insert("debts", null, cv)
    }

    fun deleteDebt(id: Long) { h.writableDatabase.delete("debts", "id=?", arrayOf(id.toString())) }

    /** Money from/to a person reduces their open debts, oldest first. Returns the amount applied. */
    fun applyDebtPayment(phone: String, txDirection: String, amount: Double, time: Long): Double {
        val p = last9(phone)
        if (p.isEmpty()) return 0.0
        val dir = if (txDirection == IN) Debt.THEY else Debt.ME
        var rest = amount
        var applied = 0.0
        for (d in debts().filter { !it.closed && it.direction == dir && last9(it.phone) == p }.sortedBy { it.created }) {
            if (rest <= 0.0001) break
            val use = minOf(rest, d.left)
            if (use <= 0) continue
            val paid = d.paid + use
            val line = java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(java.util.Date(time)) + ": " + money(use) + " by SMS"
            saveDebt(d.copy(paid = paid, closed = paid >= d.amount - 0.0001, history = (d.history + "\n" + line).trim()))
            rest -= use; applied += use
        }
        return applied
    }

    // ----- tasks -----
    fun tasks(): List<Task> = h.readableDatabase.rawQuery(
        "SELECT * FROM tasks ORDER BY done ASC, CASE WHEN due IS NULL THEN 1 ELSE 0 END, due ASC, created DESC", null
    ).use { c ->
        val out = ArrayList<Task>()
        while (c.moveToNext()) out.add(Task(c.l("id"), c.s("title") ?: "", c.l("done") == 1L,
            c.getColumnIndexOrThrow("due").let { if (c.isNull(it)) null else c.getLong(it) }, c.l("created")))
        out
    }

    fun saveTask(t: Task): Long {
        val cv = ContentValues().apply {
            put("title", t.title); put("done", if (t.done) 1 else 0); if (t.due == null) putNull("due") else put("due", t.due)
            put("created", t.created); put("done_at", if (t.done) System.currentTimeMillis() else 0)
        }
        return if (t.id > 0) { h.writableDatabase.update("tasks", cv, "id=?", arrayOf(t.id.toString())); t.id }
        else h.writableDatabase.insert("tasks", null, cv)
    }

    fun deleteTask(id: Long) { h.writableDatabase.delete("tasks", "id=?", arrayOf(id.toString())) }

    // ----- orders -----
    private fun order(c: Cursor) = Order(
        c.l("id"), c.s("kind") ?: Order.BUY, c.s("party") ?: "", c.s("phone") ?: "", c.s("title") ?: "", c.l("status").toInt(),
        c.getDouble(c.getColumnIndexOrThrow("paid_manual")), c.s("note") ?: "", c.l("created"),
        c.getColumnIndexOrThrow("due").let { if (c.isNull(it)) null else c.getLong(it) },
    )

    fun orders(kind: String? = null): List<Order> {
        val sql = "SELECT * FROM orders" + (if (kind != null) " WHERE kind=?" else "") + " ORDER BY CASE WHEN status=5 THEN 1 ELSE 0 END, created DESC"
        return h.readableDatabase.rawQuery(sql, if (kind != null) arrayOf(kind) else null).use { c ->
            val out = ArrayList<Order>(); while (c.moveToNext()) out.add(order(c)); out
        }
    }

    fun order(id: Long): Order? = h.readableDatabase.rawQuery("SELECT * FROM orders WHERE id=?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) order(c) else null
    }

    fun saveOrder(o: Order): Long {
        val cv = ContentValues().apply {
            put("kind", o.kind); put("party", o.party); put("phone", o.phone); put("title", o.title); put("status", o.status)
            put("paid_manual", o.paidManual); put("note", o.note); put("created", o.created)
            if (o.due == null) putNull("due") else put("due", o.due)
        }
        return if (o.id > 0) { h.writableDatabase.update("orders", cv, "id=?", arrayOf(o.id.toString())); o.id }
        else h.writableDatabase.insert("orders", null, cv)
    }

    fun deleteOrder(id: Long) {
        val a = arrayOf(id.toString())
        h.writableDatabase.delete("order_items", "order_id=?", a)
        h.writableDatabase.delete("shipments", "order_id=?", a)
        h.writableDatabase.execSQL("UPDATE txs SET order_id=NULL WHERE order_id=?", arrayOf<Any>(id))
        h.writableDatabase.delete("orders", "id=?", a)
    }

    fun items(orderId: Long): List<OrderItem> = h.readableDatabase.rawQuery(
        "SELECT * FROM order_items WHERE order_id=? ORDER BY id", arrayOf(orderId.toString())
    ).use { c ->
        val out = ArrayList<OrderItem>()
        while (c.moveToNext()) out.add(OrderItem(c.l("id"), c.l("order_id"), c.s("name") ?: "",
            c.getDouble(c.getColumnIndexOrThrow("qty")), c.getDouble(c.getColumnIndexOrThrow("price")), c.getDouble(c.getColumnIndexOrThrow("received"))))
        out
    }

    fun saveItem(i: OrderItem): Long {
        val cv = ContentValues().apply { put("order_id", i.orderId); put("name", i.name); put("qty", i.qty); put("price", i.price); put("received", i.received) }
        return if (i.id > 0) { h.writableDatabase.update("order_items", cv, "id=?", arrayOf(i.id.toString())); i.id }
        else h.writableDatabase.insert("order_items", null, cv)
    }

    fun deleteItem(id: Long) { h.writableDatabase.delete("order_items", "id=?", arrayOf(id.toString())) }

    fun shipments(orderId: Long): List<Shipment> = h.readableDatabase.rawQuery(
        "SELECT * FROM shipments WHERE order_id=? ORDER BY date", arrayOf(orderId.toString())
    ).use { c ->
        val out = ArrayList<Shipment>()
        while (c.moveToNext()) out.add(Shipment(c.l("id"), c.l("order_id"), c.s("label") ?: "", c.l("date"),
            c.getDouble(c.getColumnIndexOrThrow("freight")), c.l("arrived") == 1L, c.s("note") ?: ""))
        out
    }

    fun saveShipment(s: Shipment): Long {
        val cv = ContentValues().apply {
            put("order_id", s.orderId); put("label", s.label); put("date", s.date); put("freight", s.freight)
            put("arrived", if (s.arrived) 1 else 0); put("note", s.note)
        }
        return if (s.id > 0) { h.writableDatabase.update("shipments", cv, "id=?", arrayOf(s.id.toString())); s.id }
        else h.writableDatabase.insert("shipments", null, cv)
    }

    fun deleteShipment(id: Long) { h.writableDatabase.delete("shipments", "id=?", arrayOf(id.toString())) }

    fun orderTxs(orderId: Long): List<Tx> = txList("SELECT * FROM txs WHERE order_id=? ORDER BY created_at DESC", arrayOf(orderId.toString()))

    fun linkTx(txId: Long, orderId: Long?) {
        h.writableDatabase.update("txs", ContentValues().apply { if (orderId == null) putNull("order_id") else put("order_id", orderId) },
            "id=?", arrayOf(txId.toString()))
    }

    fun orderMoney(o: Order): OrderMoney {
        val items = items(o.id).sumOf { it.qty * it.price }
        val freight = shipments(o.id).sumOf { it.freight }
        val linked = orderTxs(o.id).filter { it.status == Status.COMPLETED }.sumOf { it.amount }
        return OrderMoney(items, freight, linked + o.paidManual)
    }
}
