package com.caalin.tracker

import android.accessibilityservice.AccessibilityService
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Screen-time limits for distracting apps, enforced by the auto-PIN accessibility service. */
object Focus {
    val SUGGESTED = listOf(
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.facebook.katana", "com.facebook.lite",
        "com.instagram.android", "com.google.android.youtube", "com.snapchat.android", "com.twitter.android",
    )
    private const val MAX_EXTRA = 3
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) { if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("focus", Context.MODE_PRIVATE) }

    private fun day() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    var enabled: Boolean
        get() = sp.getBoolean("on", false)
        set(v) { sp.edit().putBoolean("on", v).apply() }
    var apps: Set<String>
        get() = sp.getStringSet("apps", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("apps", HashSet(v)).apply() }
    var limitMin: Int
        get() = sp.getInt("limit", 60)
        set(v) { sp.edit().putInt("limit", v).apply() }
    var hoursOn: Boolean
        get() = sp.getBoolean("hours", false)
        set(v) { sp.edit().putBoolean("hours", v).apply() }
    var hoursStart: Int
        get() = sp.getInt("hs", 8 * 60)
        set(v) { sp.edit().putInt("hs", v).apply() }
    var hoursEnd: Int
        get() = sp.getInt("he", 17 * 60)
        set(v) { sp.edit().putInt("he", v).apply() }
    private var pauseUntil: Long
        get() = sp.getLong("pause", 0)
        set(v) { sp.edit().putLong("pause", v).apply() }
    val extrasUsed: Int get() = sp.getInt("x_${day()}", 0)
    val extrasLeft: Int get() = (MAX_EXTRA - extrasUsed).coerceAtLeast(0)

    fun usedMs(pkg: String) = sp.getLong("u_${day()}_$pkg", 0)
    private fun addUsed(pkg: String, ms: Long) { sp.edit().putLong("u_${day()}_$pkg", usedMs(pkg) + ms).apply() }
    fun usedTodayMin(): Int = (apps.sumOf { usedMs(it) } / 60_000L).toInt()

    fun inFocusHours(): Boolean {
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (hoursStart <= hoursEnd) now in hoursStart until hoursEnd else now >= hoursStart || now < hoursEnd
    }

    /** "hours", "limit" or null when the apps are allowed right now. */
    fun blockedReason(): String? {
        if (!enabled || System.currentTimeMillis() < pauseUntil) return null
        if (hoursOn && inFocusHours()) return "hours"
        if (apps.sumOf { usedMs(it) } >= limitMin * 60_000L) return "limit"
        return null
    }

    fun grantExtra(): Boolean {
        if (extrasLeft <= 0) return false
        sp.edit().putInt("x_${day()}", extrasUsed + 1).apply()
        pauseUntil = System.currentTimeMillis() + 5 * 60_000L
        return true
    }

    fun timeText(min: Int) = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)

    fun label(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    // ----- tracking (called from the accessibility service) -----
    private var cur: String? = null
    private var since = 0L
    private var lastBlock = 0L

    private fun ignored(pkg: String) = pkg == "com.android.systemui" || pkg.contains("inputmethod") ||
        pkg.contains("keyboard") || pkg.endsWith(".ime")

    fun onWindow(svc: AccessibilityService, pkg: String, className: String) {
        if (ignored(pkg) || className.contains("Toast")) return
        if (pkg == cur) return
        flush(svc)
        cur = pkg; since = System.currentTimeMillis()
        check(svc)
    }

    fun flush(ctx: Context) {
        val p = cur ?: return
        val now = System.currentTimeMillis()
        val screenOn = ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (enabled && p in apps && screenOn) addUsed(p, (now - since).coerceIn(0L, 10 * 60_000L))
        since = now
    }

    fun check(svc: AccessibilityService) {
        val p = cur ?: return
        if (p !in apps) return
        val reason = blockedReason() ?: return
        val now = System.currentTimeMillis()
        if (now - lastBlock < 2500) return
        lastBlock = now
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        svc.startActivity(Intent(svc, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            .putExtra("pkg", p).putExtra("reason", reason))
    }
}

/** Full-screen break screen shown when a limited app is opened past its limit or during focus hours. */
class BlockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Focus.init(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        render()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun render() {
        val pkg = intent.getStringExtra("pkg") ?: ""
        val hours = intent.getStringExtra("reason") == "hours"
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(DEEP, 0xFF032619.toInt()))
        }
        val col = vbox(32).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(text("⏳", 56f).apply { gravity = Gravity.CENTER }, WRAP)
        col.add(text("Time for a break", 26f, true, WHITE).apply { gravity = Gravity.CENTER }, WRAP, top = 16)
        val msg = if (hours) "Focus hours: ${Focus.label(this, pkg)} is paused until ${Focus.timeText(Focus.hoursEnd)}."
            else "You've used ${Focus.usedTodayMin()} of your ${Focus.limitMin} minutes on social apps today."
        col.add(text(msg, 16f, color = 0xDDFFFFFF.toInt()).apply { gravity = Gravity.CENTER }, MATCH, top = 10)
        col.add(text("Your business is waiting 💼", 14f, color = 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER }, MATCH, top = 18)

        col.add(text("Back to home screen", 16f, true, DEEP).apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(16), dp(24), dp(16))
            background = rounded(WHITE, dp(28).toFloat())
            setOnClickListener { goHome() }
        }, MATCH, top = 40)

        if (Focus.extrasLeft > 0) {
            col.add(text("5 more minutes (fingerprint) · ${Focus.extrasLeft} left today", 14f, true, WHITE).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(0x22FFFFFF, dp(28).toFloat())
                setOnClickListener {
                    Bio.prompt(this@BlockActivity, "5 more minutes", "Confirm with your fingerprint", null,
                        onOk = {
                            if (Focus.grantExtra()) {
                                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                                finish()
                            }
                        }, onError = { _, _ -> })
                }
            }, MATCH, top = 12)
        }
        root.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        setContentView(root)
    }
}

/** Settings for the focus lock. */
class FocusActivity : BaseActivity() {
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Focus.init(this)
        title = "Focus & screen time"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.elevation = 0f
        box = vbox(16)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onUnlockedResume() = render()

    private fun launchable(): List<Pair<String, String>> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != packageName }
            .map { it to Focus.label(this, it) }
            .sortedWith(compareBy({ if (it.first in Focus.SUGGESTED) 0 else 1 }, { it.second.lowercase() }))
    }

    private fun render() {
        box.removeAllViews()
        val all = launchable()
        if (Focus.apps.isEmpty()) Focus.apps = all.map { it.first }.filter { it in Focus.SUGGESTED }.toSet()

        // On/off
        val top = card()
        val tv = vbox(18)
        val row = hbox()
        val tt = vbox()
        tt.add(text("Limit distracting apps", 17f, true))
        tt.add(text("When time is up, the app closes and a break screen appears.", 13f, color = MUTED), top = 2)
        row.add(tt, 0, weight = 1f)
        row.add(SwitchMaterial(this).apply {
            isChecked = Focus.enabled
            setOnCheckedChangeListener { _, b -> Focus.enabled = b; render() }
        }, WRAP)
        tv.add(row)
        if (!Ussd.enabled(this)) {
            tv.add(text("Needs \"Caalin Tracker auto-PIN\" turned on in Accessibility (Settings › Setup).", 13f, true, AMBER).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(AMBER_BG, dp(10).toFloat())
            }, top = 12)
        }
        top.addView(tv)
        box.add(top)

        // Today
        box.add(sectionTitle("Today"))
        val used = Focus.usedTodayMin()
        val tc = card()
        val tcv = vbox(18)
        tcv.add(text("$used of ${Focus.limitMin} min", 24f, true, if (used >= Focus.limitMin) RED else INK))
        tcv.add(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = Focus.limitMin; progress = used.coerceAtMost(Focus.limitMin)
        }, top = 8)
        Focus.apps.map { it to Focus.usedMs(it) / 60_000L }.filter { it.second > 0 }.sortedByDescending { it.second }.forEach { (p, m) ->
            val r = hbox()
            r.add(text(Focus.label(this, p), 14f), 0, weight = 1f)
            r.add(text("$m min", 14f, true), WRAP)
            tcv.add(r, top = 8)
        }
        tc.addView(tcv)
        box.add(tc)

        // Limit
        box.add(sectionTitle("Daily limit (all chosen apps together)"))
        val lim = hbox()
        listOf(15, 30, 45, 60, 90, 120).forEach { m ->
            lim.add(pill(if (m < 60) "${m}m" else "${m / 60}h${if (m % 60 > 0) "${m % 60}" else ""}", Focus.limitMin == m) {
                Focus.limitMin = m; render()
            }, WRAP, end = 6)
        }
        box.add(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(lim) })

        // Focus hours
        box.add(sectionTitle("Focus hours"))
        val fh = card()
        val fv = vbox(18)
        val fr = hbox()
        val ft = vbox()
        ft.add(text("Block these apps during work", 15f, true))
        ft.add(text("${Focus.timeText(Focus.hoursStart)} – ${Focus.timeText(Focus.hoursEnd)} every day", 13f, color = MUTED), top = 2)
        fr.add(ft, 0, weight = 1f)
        fr.add(SwitchMaterial(this).apply {
            isChecked = Focus.hoursOn
            setOnCheckedChangeListener { _, b -> Focus.hoursOn = b }
        }, WRAP)
        fv.add(fr)
        val times = hbox()
        times.add(button("From ${Focus.timeText(Focus.hoursStart)}", outlined = true) {
            TimePickerDialog(this, { _, h, m -> Focus.hoursStart = h * 60 + m; render() }, Focus.hoursStart / 60, Focus.hoursStart % 60, true).show()
        }, 0, weight = 1f, end = 10)
        times.add(button("To ${Focus.timeText(Focus.hoursEnd)}", outlined = true) {
            TimePickerDialog(this, { _, h, m -> Focus.hoursEnd = h * 60 + m; render() }, Focus.hoursEnd / 60, Focus.hoursEnd % 60, true).show()
        }, 0, weight = 1f)
        fv.add(times, top = 12)
        fh.addView(fv)
        box.add(fh)

        // Apps
        box.add(sectionTitle("Apps to limit"))
        val chosen = Focus.apps.toMutableSet()
        val rows = all.map { (p, name) ->
            val r = hbox().apply { setPadding(dp(14), dp(10), dp(10), dp(10)) }
            val ic = ImageView(this).apply {
                try { setImageDrawable(packageManager.getApplicationIcon(p)) } catch (_: PackageManager.NameNotFoundException) {}
            }
            r.add(ic, dp(36), dp(36), end = 14)
            r.add(text(name, 15f), 0, weight = 1f)
            val cb = CheckBox(this).apply {
                isChecked = p in chosen
                setOnCheckedChangeListener { _, b -> if (b) chosen.add(p) else chosen.remove(p); Focus.apps = chosen }
            }
            r.add(cb, WRAP)
            r.setOnClickListener { cb.toggle() }
            r as View
        }
        box.add(listCard(rows))
        box.add(View(this), MATCH, dp(24))
    }
}
