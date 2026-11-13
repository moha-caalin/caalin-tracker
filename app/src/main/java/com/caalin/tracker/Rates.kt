package com.caalin.tracker

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Daily market exchange rates against the US dollar, kept for offline use. */
object Rates {
    data class Cur(val code: String, val name: String, val symbol: String, val flag: String)
    val LIST = listOf(
        Cur("USD", "US dollar", "$", "🇺🇸"),
        Cur("CNY", "Chinese yuan", "¥", "🇨🇳"),
        Cur("AED", "UAE dirham", "AED ", "🇦🇪"),
        Cur("KES", "Kenyan shilling", "KSh ", "🇰🇪"),
        Cur("EUR", "Euro", "€", "🇪🇺"),
    )
    private const val URL_RATES = "https://open.er-api.com/v6/latest/USD"
    private lateinit var sp: SharedPreferences
    @Volatile private var loading = false

    fun init(ctx: Context) { if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("rates", Context.MODE_PRIVATE) }

    /** Units of [code] for 1 US dollar. */
    fun perUsd(code: String): Double? = if (code == "USD") 1.0 else sp.getFloat(code, -1f).toDouble().takeIf { it > 0 }
    val updated: Long get() = sp.getLong("updated", 0)
    val fetched: Long get() = sp.getLong("fetched", 0)

    fun convert(amount: Double, from: String, to: String): Double? {
        val f = perUsd(from) ?: return null
        val t = perUsd(to) ?: return null
        return amount / f * t
    }

    fun fmt(v: Double, code: String): String {
        val c = LIST.firstOrNull { it.code == code }
        val digits = if (v >= 1000) "%,.0f" else if (v >= 10) "%,.2f" else "%,.3f"
        return (c?.symbol ?: "$code ") + String.format(Locale.US, digits, v)
    }

    /** Refreshes in the background when older than [maxAgeMs]; calls [done] on the main thread with success. */
    fun refresh(ctx: Context, maxAgeMs: Long = 6 * 3600_000L, done: ((Boolean) -> Unit)? = null) {
        init(ctx)
        if (loading || System.currentTimeMillis() - fetched < maxAgeMs) { done?.invoke(false); return }
        loading = true
        Thread {
            var ok = false
            try {
                val con = URL(URL_RATES).openConnection() as HttpURLConnection
                con.connectTimeout = 10_000; con.readTimeout = 10_000
                val body = con.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(body)
                if (j.optString("result") == "success") {
                    val r = j.getJSONObject("rates")
                    val e = sp.edit()
                    LIST.filter { it.code != "USD" }.forEach { c -> if (r.has(c.code)) e.putFloat(c.code, r.getDouble(c.code).toFloat()) }
                    e.putLong("updated", j.optLong("time_last_update_unix") * 1000L)
                    e.putLong("fetched", System.currentTimeMillis())
                    e.apply()
                    ok = true
                }
            } catch (_: Exception) {
            }
            loading = false
            Handler(Looper.getMainLooper()).post { done?.invoke(ok) }
        }.start()
    }
}

class RatesActivity : BaseActivity() {
    private lateinit var box: LinearLayout
    private var from = "USD"
    private var amount = "100"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Rates.init(this)
        title = "Exchange rates"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.elevation = 0f
        box = vbox(16)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onUnlockedResume() {
        render()
        Rates.refresh(this) { if (it) render() }
    }

    private fun render() {
        box.removeAllViews()
        // Today's rates
        val top = card()
        val tv = vbox(18)
        tv.add(text("Today · 1 US dollar =", 13f, true, MUTED))
        Rates.LIST.filter { it.code != "USD" }.forEach { c ->
            val r = hbox()
            r.add(text(c.flag, 22f), WRAP, end = 12)
            val m = vbox()
            m.add(text(c.name, 15f, true)); m.add(text(c.code, 12f, color = MUTED))
            r.add(m, 0, weight = 1f)
            r.add(text(Rates.perUsd(c.code)?.let { Rates.fmt(it, c.code) } ?: "—", 18f, true, BRAND), WRAP)
            tv.add(r, top = 12)
        }
        val upd = if (Rates.updated > 0) "Updated ${dateText(Rates.updated)} · market rate, your agent's rate may differ"
            else "No rates yet — connect to the internet"
        tv.add(text(upd, 12f, color = MUTED), top = 14)
        top.addView(tv)
        box.add(top)

        // Converter
        box.add(sectionTitle("Converter"))
        val cc = card()
        val cv = vbox(18)
        val pills = hbox()
        Rates.LIST.forEach { c -> pills.add(pill(c.code, from == c.code) { from = c.code; render() }, WRAP, end = 6) }
        cv.add(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pills) })
        val f = field("Amount", InputType.TYPE_CLASS_PHONE, amount).apply { textSize = 24f }
        cv.add(f, top = 12)
        val out = vbox()
        cv.add(out, top = 8)
        fun fill() {
            out.removeAllViews()
            val v = Calc.eval(f.text.toString()) ?: 0.0
            Rates.LIST.filter { it.code != from }.forEach { c ->
                val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
                r.add(text("${c.flag}  ${c.code}", 15f), 0, weight = 1f)
                r.add(text(Rates.convert(v, from, c.code)?.let { Rates.fmt(it, c.code) } ?: "—", 17f, true), WRAP)
                out.add(r)
            }
        }
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { amount = e.toString(); fill() }
        })
        fill()
        cc.addView(cv)
        box.add(cc)
        box.add(button("Update rates now", outlined = true) {
            toast("Updating…")
            Rates.refresh(this, 0) { ok -> if (ok) { render(); toast("Rates updated") } else toast("No internet — showing the last rates") }
        }, top = 14)
        box.add(text("Rates: open.er-api.com", 11.5f, color = MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
    }
}
