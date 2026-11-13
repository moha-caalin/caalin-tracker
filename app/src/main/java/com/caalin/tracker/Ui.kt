package com.caalin.tracker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import kotlin.math.abs

const val INK = 0xFF16222B.toInt()
const val MUTED = 0xFF6B7780.toInt()
const val LINE = 0xFFE6ECE9.toInt()
const val BRAND = 0xFF0B6E4F.toInt()
const val DEEP = 0xFF06402E.toInt()
const val GREEN = 0xFF1E9E5A.toInt()
const val GREEN_BG = 0xFFE4F5EB.toInt()
const val RED = 0xFFD64545.toInt()
const val RED_BG = 0xFFFBE8E8.toInt()
const val AMBER = 0xFFC77700.toInt()
const val AMBER_BG = 0xFFFFF3DC.toInt()
const val WHITE = 0xFFFFFFFF.toInt()
val PALETTE = listOf(0xFF1E8E3E, 0xFF1565C0, 0xFFD32F2F, 0xFFF57C00, 0xFF6A1B9A, 0xFF00838F, 0xFF37474F).map { it.toInt() }

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

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
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
    }

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.card(): MaterialCardView = MaterialCardView(this).apply {
    radius = dp(18).toFloat()
    cardElevation = 0f
    strokeWidth = dp(1)
    strokeColor = LINE
    setCardBackgroundColor(WHITE)
}

fun Context.button(label: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
    val b = if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    else MaterialButton(this)
    b.text = label
    b.isAllCaps = false
    b.cornerRadius = dp(14)
    b.textSize = 15f
    b.minHeight = dp(50)
    b.setOnClickListener { onClick() }
    return b
}

fun Context.field(hint: String, type: Int, value: String = ""): EditText = EditText(this).apply {
    this.hint = hint; inputType = type; setText(value); textSize = 16f
    background = rounded(0xFFF4F7F5.toInt(), dp(12).toFloat(), dp(1), LINE)
    setPadding(dp(14), dp(12), dp(14), dp(12))
}

fun Context.icon(res: Int, tint: Int, size: Int = 22): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
}

/** Circle with an icon or a letter inside. */
fun Context.badge(bg: Int, size: Int, iconRes: Int = 0, iconTint: Int = WHITE, letter: String = ""): View {
    val f = FrameLayout(this)
    f.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
    if (iconRes != 0) {
        f.addView(icon(iconRes, iconTint, size / 2), FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER))
    } else {
        f.addView(text(letter, (size / 2.4f), true, iconTint).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(MATCH, MATCH))
    }
    f.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    return f
}

fun Context.dot(color: Int, size: Int = 14): View = View(this).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(12) }
}

fun Context.pill(label: String, selected: Boolean, onDark: Boolean = false, onClick: () -> Unit): TextView =
    text(label, 14f, true, when {
        onDark && selected -> BRAND
        onDark -> 0xDDFFFFFF.toInt()
        selected -> WHITE
        else -> INK
    }).apply {
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = when {
            onDark && selected -> rounded(WHITE, dp(20).toFloat())
            onDark -> rounded(0x26FFFFFF, dp(20).toFloat())
            selected -> rounded(BRAND, dp(20).toFloat())
            else -> rounded(WHITE, dp(20).toFloat(), dp(1), LINE)
        }
        setOnClickListener { onClick() }
    }

fun LinearLayout.add(v: View, width: Int = MATCH, height: Int = WRAP, top: Int = 0, weight: Float = 0f, end: Int = 0): View {
    val lp = LinearLayout.LayoutParams(width, height, weight)
    val d = context.resources.displayMetrics.density
    lp.topMargin = (top * d).toInt()
    lp.marginEnd = (end * d).toInt()
    addView(v, lp)
    return v
}

fun Context.sectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null): View {
    val r = hbox().apply { setPadding(dp(4), dp(18), dp(4), dp(8)) }
    r.add(text(title, 18f, true), 0, weight = 1f)
    if (action != null) r.add(text(action, 14f, true, BRAND).apply { setOnClickListener { onAction?.invoke() } }, WRAP)
    return r
}

fun money(v: Double): String {
    val s = String.format(Locale.US, "%,.2f", abs(v))
    return "$" + if (s.endsWith(".00")) s.dropLast(3) else s
}
fun amountText(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
fun dateText(t: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(t))
fun dayText(t: Long): String = SimpleDateFormat("EEEE, d MMM yyyy", Locale.US).format(Date(t))

fun startOfDay(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun startOfMonth(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun statusLabel(s: String) = when (s) {
    Status.COMPLETED -> "Completed"
    Status.FAILED -> "Failed"
    else -> "Not confirmed"
}

fun statusColor(s: String) = when (s) {
    Status.COMPLETED -> GREEN
    Status.FAILED -> RED
    else -> AMBER
}

fun Context.toast(s: CharSequence) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun Context.txRow(t: Tx, onClick: () -> Unit): View {
    val incoming = t.direction == IN
    val row = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
    row.add(badge(if (incoming) GREEN_BG else RED_BG, 42, if (incoming) R.drawable.ic_in else R.drawable.ic_out,
        if (incoming) GREEN else RED), dp(42), dp(42), end = 12)
    val mid = vbox()
    mid.add(text(t.who, 15f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    val sub = (if (incoming) "Received" else "Sent") + " · ${t.walletName} · ${dateText(t.createdAt)}"
    mid.add(text(sub, 12.5f, color = MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, top = 2)
    row.add(mid, 0, weight = 1f)
    val right = vbox().apply { gravity = Gravity.END }
    val done = t.status == Status.COMPLETED
    right.add(text((if (incoming) "+" else "−") + money(t.amount), 16f, true,
        if (!done) MUTED else if (incoming) GREEN else RED).apply { gravity = Gravity.END }, WRAP)
    if (!done) right.add(text(statusLabel(t.status), 12f, true, statusColor(t.status)).apply { gravity = Gravity.END }, WRAP, top = 2)
    row.add(right, WRAP, end = 0)
    row.setOnClickListener { onClick() }
    row.background = android.util.TypedValue().let { tv ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        getDrawable(tv.resourceId)
    }
    return row
}

/** A white card holding a list of rows separated by thin lines. */
fun Context.listCard(rows: List<View>): View {
    val c = card()
    val box = vbox()
    rows.forEachIndexed { i, v ->
        if (i > 0) box.add(View(this).apply { setBackgroundColor(LINE) }, MATCH, dp(1)).also {
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(68)
        }
        box.add(v)
    }
    c.addView(box)
    return c
}

fun AppCompatActivity.showTx(t: Tx, onChanged: () -> Unit) {
    val incoming = t.direction == IN
    val body = vbox(22)
    body.add(text(if (incoming) "Received" else "Sent", 14f, true, if (incoming) GREEN else RED))
    body.add(text((if (incoming) "+" else "−") + money(t.amount), 30f, true), top = 2)
    body.add(text(t.who + if (t.name.isNotBlank() && t.phone.isNotBlank()) "  (${t.phone})" else "", 15f), top = 6)
    body.add(text("${t.walletName} · ${dateText(t.createdAt)}", 13f, color = MUTED), top = 2)
    body.add(text(statusLabel(t.status) + if (t.source == "SMS") " · from SMS" else " · sent from app", 13f, true,
        statusColor(t.status)), top = 8)
    t.balance?.let { body.add(text("Balance after: ${money(it)}", 13f, color = MUTED), top = 4) }
    body.add(text("Message", 13f, true, MUTED), top = 16)
    body.add(text(t.response, 13f).apply {
        setTextIsSelectable(true)
        background = rounded(0xFFF4F7F5.toInt(), dp(10).toFloat())
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }, top = 6)
    val b = AlertDialog.Builder(this)
        .setView(android.widget.ScrollView(this).apply { addView(body) })
        .setPositiveButton("Close", null)
        .setNeutralButton("Delete") { _, _ ->
            AlertDialog.Builder(this).setMessage("Delete this transaction?")
                .setPositiveButton("Delete") { _, _ -> Db.deleteTx(t.id); onChanged() }
                .setNegativeButton("Cancel", null).show()
        }
    if (t.status != Status.COMPLETED) {
        b.setNegativeButton("Mark completed") { _, _ -> Db.updateTx(t.copy(status = Status.COMPLETED)); onChanged() }
    }
    b.show()
}
