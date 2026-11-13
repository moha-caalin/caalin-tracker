package com.caalin.tracker

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

/** Small, safe calculator: + − × ÷ % and brackets. Returns null when the sum is not finished. */
object Calc {
    fun eval(input: String): Double? {
        val s = input.replace('×', '*').replace('x', '*').replace('X', '*').replace('÷', '/').replace('−', '-')
            .replace(",", "").replace(" ", "").replace("$", "")
        if (s.isEmpty()) return null
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        lateinit var expr: () -> Double?
        fun number(): Double? {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDoubleOrNull()
        }
        fun factor(): Double? {
            return when (peek()) {
                '-' -> { i++; factor()?.let { -it } }
                '+' -> { i++; factor() }
                '(' -> { i++; val v = expr(); if (peek() == ')') i++; v }
                else -> number()
            }?.let { v -> if (peek() == '%') { i++; v / 100 } else v }
        }
        fun term(): Double? {
            var v = factor() ?: return null
            while (peek() == '*' || peek() == '/') {
                val op = s[i++]
                val r = factor() ?: return null
                v = if (op == '*') v * r else { if (r == 0.0) return null; v / r }
            }
            return v
        }
        expr = {
            var v = term()
            while (v != null && (peek() == '+' || peek() == '-')) {
                val op = s[i++]
                val r = term()
                v = if (r == null) null else if (op == '+') v + r else v - r
            }
            v
        }
        val v = expr() ?: return null
        return if (i == s.length && v.isFinite()) Math.round(v * 10000.0) / 10000.0 else null
    }

    fun hasOperator(s: String) = s.drop(1).any { it in "+-*/×÷−%x" }

    /** A pop-up calculator. onUse gets the result when the user taps "Use amount". */
    fun show(ctx: Context, start: String = "", onUse: ((Double) -> Unit)? = null) {
        val box = ctx.vbox(18)
        var exp = start
        val shown = ctx.text(exp.ifEmpty { "0" }, 30f, true).apply { gravity = Gravity.END; maxLines = 2 }
        val result = ctx.text("", 16f, color = MUTED).apply { gravity = Gravity.END }
        box.add(shown)
        box.add(result, top = 2)
        fun refresh() {
            shown.text = exp.ifEmpty { "0" }
            val v = eval(exp)
            result.text = if (v != null && hasOperator(exp)) "= " + amountText(v) else ""
        }
        val keys = listOf(
            listOf("C", "⌫", "%", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"), listOf("00", "0", ".", "="),
        )
        for (row in keys) {
            val r = ctx.hbox()
            for (k in row) {
                val op = k in listOf("÷", "×", "−", "+", "%")
                val bg = when { k == "=" -> BRAND; k == "C" || k == "⌫" -> RED_BG; op -> GREEN_BG; else -> 0xFFF1F4F2.toInt() }
                val fg = when { k == "=" -> WHITE; k == "C" || k == "⌫" -> RED; op -> BRAND; else -> INK }
                r.add(ctx.text(k, 22f, true, fg).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, ctx.dp(14), 0, ctx.dp(14))
                    background = rounded(bg, ctx.dp(16).toFloat())
                    setOnClickListener {
                        when (k) {
                            "C" -> exp = ""
                            "⌫" -> exp = exp.dropLast(1)
                            "=" -> eval(exp)?.let { exp = amountText(it) }
                            else -> exp += k
                        }
                        refresh()
                    }
                }, 0, weight = 1f, end = if (k != row.last()) 8 else 0)
            }
            box.add(r, top = 8)
        }
        refresh()
        val b = AlertDialog.Builder(ctx).setView(box).setNegativeButton("Close", null)
        if (onUse != null) b.setPositiveButton("Use amount") { _, _ ->
            val v = eval(exp)
            if (v != null && v > 0) onUse(v) else ctx.toast("Finish the sum first")
        }
        b.show()
    }
}
