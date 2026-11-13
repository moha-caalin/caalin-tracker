package com.caalin.tracker

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val INK = 0xFF1F2933.toInt()
const val MUTED = 0xFF6B7780.toInt()
const val BRAND = 0xFF0B6E4F.toInt()
val PALETTE = listOf(0xFF1E8E3E, 0xFF1565C0, 0xFFD32F2F, 0xFFF57C00, 0xFF6A1B9A, 0xFF00838F, 0xFF37474F).map { it.toInt() }

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.vbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.text(s: CharSequence, size: Float = 15f, bold: Boolean = false, color: Int = INK): TextView =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.card(): MaterialCardView = MaterialCardView(this).apply {
    radius = dp(14).toFloat()
    cardElevation = dp(1).toFloat()
    useCompatPadding = true
}

fun Context.button(label: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
    val b = if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    else MaterialButton(this)
    b.text = label
    b.isAllCaps = false
    b.setOnClickListener { onClick() }
    return b
}

fun Context.field(hint: String, type: Int, value: String = ""): EditText = EditText(this).apply {
    this.hint = hint; inputType = type; setText(value)
}

fun Context.dot(color: Int, size: Int = 14): View = View(this).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(12) }
}

fun LinearLayout.add(
    v: View,
    width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    top: Int = 0,
    weight: Float = 0f,
): View {
    val lp = LinearLayout.LayoutParams(width, height, weight)
    lp.topMargin = (top * context.resources.displayMetrics.density).toInt()
    addView(v, lp)
    return v
}

fun money(v: Double): String = "$" + String.format(Locale.US, "%,.2f", v)
fun amountText(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
fun dateText(t: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(t))

fun startOfDay(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun statusLabel(s: String) = when (s) {
    Status.COMPLETED -> "Completed"
    Status.FAILED -> "Failed"
    else -> "Not confirmed"
}

fun statusColor(s: String) = when (s) {
    Status.COMPLETED -> 0xFF1E8E3E.toInt()
    Status.FAILED -> 0xFFD32F2F.toInt()
    else -> 0xFFC77700.toInt()
}

fun Context.toast(s: CharSequence) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun Context.txRow(t: Tx, onClick: () -> Unit): View {
    val row = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
    val left = vbox()
    left.add(text("${t.walletName} → ${t.phone}", 15f, true))
    left.add(text(dateText(t.createdAt), 12f, color = MUTED), top = 2)
    row.add(left, 0, weight = 1f)
    val right = vbox().apply { gravity = Gravity.END }
    right.add(text(money(t.amount), 16f, true).apply { gravity = Gravity.END })
    right.add(text(statusLabel(t.status), 12f, true, statusColor(t.status)).apply { gravity = Gravity.END }, top = 2)
    row.add(right, ViewGroup.LayoutParams.WRAP_CONTENT)
    row.setOnClickListener { onClick() }
    return card().apply { addView(row) }
}

fun AppCompatActivity.showTx(t: Tx, onChanged: () -> Unit) {
    val body = vbox(20)
    body.add(text(money(t.amount), 24f, true))
    body.add(text("${t.walletName} → ${t.phone}", 15f), top = 4)
    body.add(text(dateText(t.createdAt), 13f, color = MUTED), top = 2)
    body.add(text(statusLabel(t.status), 14f, true, statusColor(t.status)), top = 8)
    body.add(text("USSD reply", 13f, true, MUTED), top = 16)
    body.add(text(t.response, 13f).apply { setTextIsSelectable(true) }, top = 4)
    AlertDialog.Builder(this)
        .setView(android.widget.ScrollView(this).apply { addView(body) })
        .setPositiveButton("Close", null)
        .setNeutralButton("Delete") { _, _ ->
            AlertDialog.Builder(this).setMessage("Delete this transaction?")
                .setPositiveButton("Delete") { _, _ -> Db.deleteTx(t.id); onChanged() }
                .setNegativeButton("Cancel", null).show()
        }
        .show()
}
