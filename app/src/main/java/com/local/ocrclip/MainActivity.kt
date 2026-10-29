package com.local.ocrclip

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Collections
import java.util.Locale

/**
 * Settings screen, built in code: a navigation rail with four sections (Capture, Lookup, Translate, Service), each
 * a two-column page of Material 3 card groups sized for a 640×456 dp landscape window (Retroid Pocket Nova).
 * Every widget registers a refresher that redraws it from [Prefs], so a click only writes the preference and the
 * preference listener updates the screen.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var localDictionary: LocalDictionary
    private lateinit var p: Palette

    private enum class Section(val title: String, val icon: Int) {
        CAPTURE("Capture", R.drawable.ic_capture),
        LOOKUP("Lookup", R.drawable.ic_book),
        TRANSLATE("Translate", R.drawable.ic_translate),
        SERVICE("Service", R.drawable.ic_service),
    }

    private enum class Binding { LOOKUP, TRANSLATE }

    private enum class Style { TONAL, OUTLINED, FILLED_ERROR, TEXT }

    private class NavItem(val root: View, val pill: View, val icon: ImageView, val label: TextView)

    private var section = Section.CAPTURE
    private val navItems = mutableMapOf<Section, NavItem>()
    private val pages = mutableMapOf<Section, View>()
    private lateinit var title: TextView

    /** Redraw one widget from the preferences; all of them run on every preference change. */
    private val refreshers = mutableListOf<() -> Unit>()
    private var serviceOn = false
    private var canReEnable = false

    private val previews = mutableListOf<RegionPreview>()
    private lateinit var dictGroup: LinearLayout
    private lateinit var dictHelp: TextView

    /** Which hotkey the next key press binds, or null when not binding. */
    private var binding: Binding? = null
    private var bindDialog: Dialog? = null
    private var keyVisible = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        localDictionary = LocalDictionary(this)
        p = Palette(this)
        // Pure black, so the Retroids' OLED screens switch those pixels off.
        window.decorView.setBackgroundColor(Color.BLACK)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(buildRoot())
        val saved = savedInstanceState?.getString(KEY_SECTION)
        showSection(Section.entries.firstOrNull { it.name == saved } ?: Section.CAPTURE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SECTION, section.name)
    }

    override fun onResume() {
        super.onResume()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        loadScreenshot()
        refresh()
        showDictionaries()
    }

    override fun onPause() {
        super.onPause()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        bindDialog?.dismiss()
        stopBinding()
        localDictionary.close()
    }

    private fun refresh() {
        serviceOn = ServiceEnabler.ensureEnabled(this)
        canReEnable = ServiceEnabler.canEnable(this)
        refreshers.forEach { it() }
    }

    // ---- Frame: rail, header, pages ----

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        // Only matters where the app is drawn edge to edge (Android 15+); elsewhere the insets are zero.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(buildRail(), LinearLayout.LayoutParams(dpi(80), MATCH))

        val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(20), dpi(10), dpi(12), dpi(6))
        }
        title = label(22f, p.on)
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(buildStatusChip(), LinearLayout.LayoutParams(WRAP, dpi(36)))
        pane.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val content = FrameLayout(this)
        for (s in Section.entries) {
            val page = when (s) {
                Section.CAPTURE -> capturePage()
                Section.LOOKUP -> lookupPage()
                Section.TRANSLATE -> translatePage()
                Section.SERVICE -> servicePage()
            }
            pages[s] = page
            content.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        pane.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(pane, LinearLayout.LayoutParams(0, MATCH, 1f))
        return root
    }

    private fun buildRail(): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpi(14), 0, dpi(10))
        }
        val logo = FrameLayout(this).apply { background = shape(radiiAll(12), p.pc) }
        logo.addView(icon(R.drawable.ic_capture, p.onPc), FrameLayout.LayoutParams(dpi(22), dpi(22), Gravity.CENTER))
        rail.addView(logo, LinearLayout.LayoutParams(dpi(40), dpi(40)))

        for ((i, s) in Section.entries.withIndex()) {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dpi(2), 0, dpi(4))
                isFocusable = true
                isClickable = true
                background = tappable(p, radiiAll(16), Color.TRANSPARENT)
                setOnClickListener { showSection(s) }
            }
            val pill = FrameLayout(this)
            val ic = icon(s.icon, p.onVar)
            pill.addView(ic, FrameLayout.LayoutParams(dpi(24), dpi(24), Gravity.CENTER))
            if (s == Section.SERVICE) {
                val badge = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(p.error)
                    }
                    contentDescription = "Needs attention"
                }
                pill.addView(badge, FrameLayout.LayoutParams(dpi(8), dpi(8), Gravity.TOP or Gravity.END).apply {
                    topMargin = dpi(4)
                    marginEnd = dpi(12)
                })
                refreshers += { badge.visibility = if (serviceOn) View.GONE else View.VISIBLE }
            }
            root.addView(pill, LinearLayout.LayoutParams(dpi(56), dpi(32)))
            val text = label(12f, p.onVar, value = s.title).apply { gravity = Gravity.CENTER }
            root.addView(text, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(4) })
            navItems[s] = NavItem(root, pill, ic, text)
            rail.addView(root, LinearLayout.LayoutParams(dpi(72), WRAP).apply { topMargin = dpi(if (i == 0) 24 else 10) })
        }

        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(buildPowerToggle(), LinearLayout.LayoutParams(dpi(72), WRAP))
        return rail
    }

    /** "Hotkey active", reachable from every section: pauses both hotkeys so the buttons go back to the game. */
    private fun buildPowerToggle(): View {
        val toggle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpi(4), 0, dpi(4))
            isFocusable = true
            isClickable = true
            background = tappable(p, radiiAll(18), Color.TRANSPARENT)
            contentDescription = "Hotkeys"
            setOnClickListener { prefs.enabled = !prefs.enabled }
            announceAs("android.widget.Switch") { prefs.enabled }
        }
        val box = FrameLayout(this)
        val ic = icon(R.drawable.ic_power, p.onPc)
        box.addView(ic, FrameLayout.LayoutParams(dpi(24), dpi(24), Gravity.CENTER))
        toggle.addView(box, LinearLayout.LayoutParams(dpi(56), dpi(56)))
        val text = label(12f, p.onVar, medium = true)
        toggle.addView(text, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(4) })
        refreshers += {
            val on = prefs.enabled
            box.background = shape(radiiAll(16), if (on) p.pc else p.cardHigh)
            tint(ic, if (on) p.onPc else p.onVar)
            text.text = if (on) "Active" else "Paused"
        }
        return toggle
    }

    private fun buildStatusChip(): View {
        val chip = button("", Style.OUTLINED) { showSection(Section.SERVICE) }
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        chip.setPadding(dpi(12), 0, dpi(14), 0)
        chip.compoundDrawablePadding = dpi(8)
        refreshers += {
            val (text, fill, fg, dotColor) = when {
                !serviceOn -> Quad("Service off", p.ec, p.onEc, p.error)
                prefs.enabled -> Quad("Ready", Color.TRANSPARENT, p.on, p.primary)
                else -> Quad("Paused", Color.TRANSPARENT, p.on, p.outline)
            }
            chip.text = text
            chip.setTextColor(fg)
            chip.background = tappable(p, radiiAll(10), fill, if (serviceOn) p.outlineVar else p.ec, dpi(1))
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
                setSize(dpi(8), dpi(8))
            }
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        }
        return chip
    }

    private data class Quad(val text: String, val fill: Int, val fg: Int, val dot: Int)

    private fun showSection(s: Section) {
        section = s
        title.text = s.title
        for ((key, page) in pages) page.visibility = if (key == s) View.VISIBLE else View.GONE
        for ((key, item) in navItems) {
            val on = key == s
            item.pill.background = if (on) shape(radiiAll(16), p.sc) else null
            tint(item.icon, if (on) p.onSc else p.onVar)
            item.label.setTextColor(if (on) p.on else p.onVar)
            item.label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            item.root.isSelected = on
        }
    }

    /** Two scrolling columns. */
    private fun page(fill: (left: LinearLayout, right: LinearLayout) -> Unit): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(12), dpi(6), dpi(12), dpi(16))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(right, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dpi(12) })
        scroll.addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
        fill(left, right)
        return scroll
    }

    /** A section heading and the vertical box under it, with 14 dp between sections. */
    private fun LinearLayout.section(heading: String?): LinearLayout {
        val box = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        if (heading != null) {
            box.addView(label(13f, p.primary, medium = true, value = heading).apply {
                setPadding(dpi(4), 0, 0, dpi(8))
            })
        }
        addView(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (childCount > 0) topMargin = dpi(14) })
        return box
    }

    /** Adds a card to a group, 2 dp below the previous one. */
    private fun LinearLayout.card(view: View) {
        addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (childCount > 0) topMargin = dpi(2) })
    }

    // ---- Capture ----

    private fun capturePage() = page { left, right ->
        left.section("Hotkey").card(keyCard("Lookup hotkey", { prefs.hotkey }, null) { startBinding(Binding.LOOKUP) })

        val engine = left.section("OCR engine")
        engine.addView(segmented(), LinearLayout.LayoutParams(MATCH, dpi(48)))
        val note = LinearLayout(this).apply { setPadding(dpi(6), dpi(10), dpi(6), 0) }
        val noteIcon = icon(R.drawable.ic_cloud, p.onVar)
        note.addView(noteIcon, LinearLayout.LayoutParams(dpi(18), dpi(18)).apply {
            marginEnd = dpi(10)
            topMargin = dpi(1)
        })
        val noteText = label(13f, p.onVar)
        note.addView(noteText, LinearLayout.LayoutParams(0, WRAP, 1f))
        engine.addView(note)
        refreshers += {
            val lens = prefs.engine == Prefs.ENGINE_LENS
            noteIcon.setImageResource(if (lens) R.drawable.ic_cloud else R.drawable.ic_lock)
            noteText.text = if (lens) "Best on pixel fonts. Needs internet and sends the captured image to Google."
            else "Offline and private, but less accurate on pixel fonts."
        }

        val last = row(Pos.SINGLE, minHeight = 64).apply { setPadding(dpi(16), dpi(10), dpi(4), dpi(10)) }
        val lastText = label(16f, p.on).apply {
            setTextIsSelectable(true)
            textLocale = Locale.JAPANESE
        }
        last.addView(lastText, LinearLayout.LayoutParams(0, WRAP, 1f))
        last.addView(iconButton(R.drawable.ic_copy, "Copy last result") {
            if (prefs.lastResult.isEmpty()) return@iconButton
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("OCR Clip", prefs.lastResult))
            // Android 13+ confirms clipboard writes itself.
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        })
        left.section("Last result").card(last)
        refreshers += {
            lastText.text = prefs.lastResult.ifEmpty { "Nothing captured yet" }
            lastText.setTextColor(if (prefs.lastResult.isEmpty()) p.onVar else p.on)
        }

        right.section("Capture region").card(regionCard())

        val after = right.section("After reading")
        after.card(switchRow(Pos.TOP, "Copy to clipboard", { prefs.copyToClipboard }) { prefs.copyToClipboard = it })
        after.card(switchRow(Pos.MIDDLE, "Show as a toast", { prefs.showToast }) { prefs.showToast = it })
        after.card(switchRow(Pos.BOTTOM, "Keep line breaks", { prefs.keepNewlines }) { prefs.keepNewlines = it })
    }

    /** On-device | Google Lens, as one outlined bar. */
    private fun segmented(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(1), dpi(1), dpi(1), dpi(1))
            background = shape(radiiAll(24), Color.TRANSPARENT, p.outline, dpi(1))
        }
        val options = listOf(Prefs.ENGINE_LOCAL to "On-device", Prefs.ENGINE_LENS to "Google Lens")
        for ((i, option) in options.withIndex()) {
            val (value, name) = option
            val r = dp(23f)
            val corners = if (i == 0) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            val seg = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setOnClickListener { prefs.engine = value }
                announceAs("android.widget.RadioButton") { prefs.engine == value }
            }
            val check = icon(R.drawable.ic_check, p.onSc)
            seg.addView(check, LinearLayout.LayoutParams(dpi(18), dpi(18)).apply { marginEnd = dpi(6) })
            val text = label(14f, p.on, medium = true, value = name)
            seg.addView(text)
            if (i == 1) bar.addView(View(this).apply { setBackgroundColor(p.outline) }, LinearLayout.LayoutParams(dpi(1), MATCH))
            bar.addView(seg, LinearLayout.LayoutParams(0, MATCH, 1f))
            refreshers += {
                val on = prefs.engine == value
                seg.background = tappable(p, corners, if (on) p.sc else Color.TRANSPARENT)
                check.visibility = if (on) View.VISIBLE else View.GONE
                text.setTextColor(if (on) p.onSc else p.on)
            }
        }
        return bar
    }

    private fun regionCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            background = shape(radii(Pos.SINGLE), p.card)
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val preview = RegionPreview(this, p, popup = false)
        previews += preview
        top.addView(preview)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = label(16f, p.on)
        val detail = label(13f, p.onVar)
        texts.addView(name)
        texts.addView(detail)
        top.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dpi(12) })
        card.addView(top)

        val buttons = LinearLayout(this)
        buttons.addView(button("Pick region", Style.TONAL, R.drawable.ic_crop) {
            if (Prefs.lastScreenshot(this).exists()) {
                startActivity(Intent(this, RegionActivity::class.java))
            } else {
                Toast.makeText(this, "Press the hotkey in your game once first, then pick the region on that screenshot.", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(0, dpi(44), 1f))
        buttons.addView(button("Full screen", Style.OUTLINED) { prefs.region = null },
            LinearLayout.LayoutParams(0, dpi(44), 1f).apply { marginStart = dpi(8) })
        card.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(12) })

        refreshers += {
            val r = prefs.region
            preview.region = r
            name.text = if (r == null) "Full screen" else "Text box only"
            detail.text = r?.let {
                "%d–%d%% across, %d–%d%% down".format(
                    (it.left * 100).toInt(), (it.right * 100).toInt(), (it.top * 100).toInt(), (it.bottom * 100).toInt(),
                )
            } ?: "The whole screen is read"
        }
        return card
    }

    /** The last screenshot, shrunk to thumbnail size, behind the region previews. */
    private fun loadScreenshot() {
        val file = Prefs.lastScreenshot(this)
        val bitmap = if (file.exists()) {
            runCatching {
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })
            }.getOrNull()
        } else null
        previews.forEach { it.screenshot = bitmap }
    }

    // ---- Lookup ----

    private fun lookupPage() = page { left, right ->
        val modes = left.section("After capture")
        val options = listOf(
            Triple(Prefs.DICT_OFF, "Just copy", "Clipboard only"),
            Triple(Prefs.DICT_OVERLAY, "Web dictionary", "Opens over the game"),
            Triple(Prefs.DICT_LOCAL, "Yomitan, offline", "Your own dictionaries"),
            Triple(Prefs.DICT_BROWSER, "Browser", "Opens in your browser app"),
        )
        for ((i, option) in options.withIndex()) {
            val (value, name, sub) = option
            val pos = when (i) {
                0 -> Pos.TOP
                options.lastIndex -> Pos.BOTTOM
                else -> Pos.MIDDLE
            }
            val radio = Indicator(this, p, Indicator.Kind.RADIO)
            val r = row(pos, minHeight = 62) { prefs.dictionaryMode = value }
            r.announceAs("android.widget.RadioButton") { prefs.dictionaryMode == value }
            r.addView(radio, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dpi(14) })
            val (texts, nameView, subView) = twoLine(name, sub)
            r.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            modes.card(r)
            refreshers += {
                val on = prefs.dictionaryMode == value
                radio.checked = on
                r.background = tappable(p, radii(pos), if (on) p.sc else p.card)
                nameView.setTextColor(if (on) p.onSc else p.on)
                subView.setTextColor(if (on) p.onSc else p.onVar)
            }
        }
        val note = LinearLayout(this).apply { setPadding(dpi(6), dpi(12), dpi(6), 0) }
        note.addView(icon(R.drawable.ic_gamepad, p.onVar), LinearLayout.LayoutParams(dpi(18), dpi(18)).apply {
            marginEnd = dpi(10)
            topMargin = dpi(1)
        })
        val noteText = label(13f, p.onVar).apply { textLocale = Locale.JAPANESE }
        note.addView(noteText, LinearLayout.LayoutParams(0, WRAP, 1f))
        left.addView(note)
        refreshers += {
            val text = when (prefs.dictionaryMode) {
                Prefs.DICT_LOCAL -> "Tap a character, or use D-pad left and right, to pick the word. 食べなかった finds 食べる."
                Prefs.DICT_OVERLAY -> "B, Back, the hotkey or a tap outside closes it."
                Prefs.DICT_BROWSER -> "The game keeps running in the background."
                else -> ""
            }
            noteText.text = text
            note.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }

        // Right column: what the chosen mode needs. Built once and shown or hidden, so typing keeps focus.
        val local = right.section("Yomitan dictionaries")
        dictGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        local.addView(dictGroup)
        dictHelp = label(13f, p.onVar).apply { setPadding(dpi(6), dpi(10), dpi(6), 0) }
        local.addView(dictHelp)

        val web = right.section("Web dictionary")
        val (urlField, urlEdit) = field("URL template", prefs.dictionaryUrl, InputType.TYPE_TEXT_VARIATION_URI) {
            prefs.dictionaryUrl = it.ifEmpty { Prefs.DEFAULT_DICTIONARY_URL }
        }
        web.addView(urlField)
        web.addView(helper("{text} becomes the captured text."))
        web.addView(button("Reset to default", Style.OUTLINED) { urlEdit.setText(Prefs.DEFAULT_DICTIONARY_URL) },
            LinearLayout.LayoutParams(WRAP, dpi(44)).apply { topMargin = dpi(16) })

        val off = right.section(null).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(24), dpi(56), dpi(24), 0)
        }
        off.addView(icon(R.drawable.ic_copy, p.onVar), LinearLayout.LayoutParams(dpi(40), dpi(40)))
        off.addView(label(14f, p.onVar, value = "Captured text only goes to the clipboard. Pick a dictionary to look words up after every capture.").apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(12) })

        refreshers += {
            val mode = prefs.dictionaryMode
            local.visibility = if (mode == Prefs.DICT_LOCAL) View.VISIBLE else View.GONE
            web.visibility = if (mode == Prefs.DICT_OVERLAY || mode == Prefs.DICT_BROWSER) View.VISIBLE else View.GONE
            off.visibility = if (mode == Prefs.DICT_OFF) View.VISIBLE else View.GONE
        }
    }

    /**
     * The database card and one row per dictionary: tap to use it or not, ▲▼ for the display order. [focus] puts
     * D-pad focus back where it was after a rebuild: (dictionary index, 0 = row, -1 = up, 1 = down).
     */
    private fun showDictionaries(focus: Pair<Int, Int>? = null) {
        dictGroup.removeAllViews()
        val dictionaries = localDictionary.dictionaries()
        val db = row(if (dictionaries.isNullOrEmpty()) Pos.SINGLE else Pos.TOP, minHeight = 64).apply {
            setPadding(dpi(16), dpi(8), dpi(10), dpi(8))
        }
        db.addView(icon(R.drawable.ic_database, p.onVar), LinearLayout.LayoutParams(dpi(24), dpi(24)).apply { marginEnd = dpi(12) })
        val summary = when {
            dictionaries == null && localDictionary.error != null -> "Can't be opened"
            dictionaries == null -> "None loaded"
            else -> {
                val size = LocalDictionary.file(this).length() / 1_000_000
                "${dictionaries.count { it.enabled }} of ${dictionaries.size} in use · $size MB"
            }
        }
        db.addView(twoLine("dict.db", summary).first, LinearLayout.LayoutParams(0, WRAP, 1f))
        db.addView(button("Load…", Style.TONAL) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_DATABASE,
            )
        }, LinearLayout.LayoutParams(WRAP, dpi(40)))
        dictGroup.card(db)

        if (dictionaries == null) {
            dictHelp.text = localDictionary.error?.let { "The database can't be opened: $it." }
                ?: "Build one on a PC from Yomitan dictionaries with tools/build_dict.py, copy it to this device, then load it here."
            dictHelp.visibility = View.VISIBLE
            return
        }
        dictHelp.visibility = View.GONE
        val titles = dictionaries.map { it.title }
        dictionaries.forEachIndexed { i, d ->
            val counts = listOf(d.terms to "terms", d.meta to "frequency/pitch", d.kanji to "kanji")
                .filter { it.first > 0 }.joinToString(", ") { "%,d %s".format(it.first, it.second) }
            val check = Indicator(this, p, Indicator.Kind.CHECK).apply { checked = d.enabled }
            val r = row(if (i == dictionaries.lastIndex) Pos.BOTTOM else Pos.MIDDLE) {
                prefs.disabledDictionaries =
                    if (d.enabled) prefs.disabledDictionaries + d.title else prefs.disabledDictionaries - d.title
                showDictionaries(i to 0)
            }.apply { setPadding(dpi(16), dpi(4), dpi(2), dpi(4)) }
            r.announceAs("android.widget.CheckBox") { d.enabled }
            r.addView(check, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dpi(16) })
            val (texts, nameView, _) = twoLine(d.title, counts)
            nameView.textLocale = Locale.JAPANESE
            nameView.setTextColor(if (d.enabled) p.on else p.onVar)
            r.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            val moves = listOf(-1 to R.drawable.ic_arrow_up, 1 to R.drawable.ic_arrow_down)
            val moveButtons = moves.map { (by, res) ->
                iconButton(res, if (by < 0) "Move ${d.title} up" else "Move ${d.title} down", size = 44) {
                    val order = titles.toMutableList()
                    Collections.swap(order, i, i + by)
                    prefs.dictionaryOrder = order
                    showDictionaries(i + by to by)
                }.apply {
                    isEnabled = i + by in dictionaries.indices
                    alpha = if (isEnabled) 1f else 0.38f
                }
            }
            moveButtons.forEach { r.addView(it) }
            dictGroup.card(r)
            if (focus?.first == i) {
                val target = when (focus.second) {
                    -1 -> moveButtons[0]
                    1 -> moveButtons[1]
                    else -> r
                }
                (if (target.isEnabled) target else r).requestFocus()
            }
        }
    }

    // ---- Translate ----

    private fun translatePage() = page { left, right ->
        left.section("Hotkey").card(keyCard("Translate hotkey", { prefs.translateHotkey }, {
            prefs.translateHotkey = KeyEvent.KEYCODE_UNKNOWN
        }) { startBinding(Binding.TRANSLATE) })

        val deepl = left.section("DeepL API")
        val passwordType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val visibleType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val (keyField, keyEdit) = field("API key", prefs.deeplKey, InputType.TYPE_TEXT_VARIATION_PASSWORD, mono = true) {
            prefs.deeplKey = it
        }
        keyEdit.setPadding(dpi(16), 0, dpi(52), 0)
        keyField.addView(iconButton(R.drawable.ic_eye, "Show API key") {
            keyVisible = !keyVisible
            val at = keyEdit.selectionEnd
            keyEdit.inputType = if (keyVisible) visibleType else passwordType
            keyEdit.typeface = Typeface.MONOSPACE
            keyEdit.setSelection(at.coerceIn(0, keyEdit.length()))
            it.contentDescription = if (keyVisible) "Hide API key" else "Show API key"
        }, FrameLayout.LayoutParams(dpi(48), dpi(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dpi(12)
            marginEnd = dpi(4)
        })
        deepl.addView(keyField)
        val keyHelp = helper("")
        deepl.addView(keyHelp)
        refreshers += {
            val key = prefs.deeplKey
            keyHelp.text = when {
                key.isEmpty() -> "Empty: the translator page below is used"
                key.endsWith(":fx") -> "Free API plan key"
                else -> "Pro API key"
            }
        }
        val target = TextView(this).apply {
            id = View.generateViewId()
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(p.on)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dpi(16), 0, dpi(12), 0)
            isFocusable = true
            isClickable = true
            background = tappable(p, radiiAll(6), Color.TRANSPARENT, p.outline, dpi(1))
            val chevron = getDrawable(R.drawable.ic_expand)!!.mutate().apply { setTint(p.onVar) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null)
            setOnClickListener { pickTarget() }
        }
        deepl.addView(outlined("Translate into", target).first, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(10) })
        refreshers += { target.text = targetName(prefs.deeplTarget) }

        val popup = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            background = shape(radii(Pos.SINGLE), p.card)
        }
        val preview = RegionPreview(this, p, popup = true)
        previews += preview
        popup.addView(preview)
        popup.addView(label(13f, p.onVar, value = "The translation pops up at the edge away from your capture region. The game keeps the controls."),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dpi(12) })
        right.section("Popup").card(popup)
        refreshers += { preview.region = prefs.region }

        val fallback = right.section("Without a key")
        val (urlField, _) = field("Translator URL", prefs.translateUrl, InputType.TYPE_TEXT_VARIATION_URI) {
            prefs.translateUrl = it.ifEmpty { Prefs.DEFAULT_TRANSLATE_URL }
        }
        fallback.addView(urlField)
        fallback.addView(helper("Opens over the game when there's no key or the API call fails. {text} becomes the captured text."))
    }

    // ---- Service ----

    private fun servicePage() = page { left, right ->
        val status = left.section("Accessibility service")
        var shown: Boolean? = null
        refreshers += {
            if (shown != serviceOn) {
                shown = serviceOn
                status.removeViews(1, status.childCount - 1)
                status.card(serviceCard(serviceOn))
            }
        }

        val reboot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(14), dpi(16), dpi(14))
            background = shape(radii(Pos.SINGLE), p.card)
        }
        val (rebootTexts, rebootTitle, rebootSub) = twoLine("", "")
        reboot.addView(rebootTexts)
        val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val code = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(6), dpi(4), dpi(6))
            background = shape(radiiAll(12), p.cardHigh)
        }
        code.addView(label(11f, p.on, value = command).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, WRAP, 1f))
        code.addView(iconButton(R.drawable.ic_copy, "Copy command", size = 44) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb command", command))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        })
        reboot.addView(code, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(10) })
        left.section("After a reboot").card(reboot)
        refreshers += {
            rebootTitle.text = if (canReEnable) "Auto re-enable is on" else "Auto re-enable is off"
            rebootSub.text = if (canReEnable) "OCR Clip turns itself back on after the firmware resets it."
            else "Retroid firmware turns the service off on boot. Run this once over adb:"
            code.visibility = if (canReEnable) View.GONE else View.VISIBLE
        }

        val privacy = right.section("What leaves this device")
        val rows = listOf(Pos.TOP, Pos.MIDDLE, Pos.BOTTOM).map { pos ->
            val r = row(pos, minHeight = 60)
            val ic = icon(R.drawable.ic_lock, p.onVar)
            r.addView(ic, LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginEnd = dpi(14) })
            val (texts, name, sub) = twoLine("", "", titleSize = 15f, subSize = 12f)
            r.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            privacy.card(r)
            Triple(ic, name, sub)
        }
        refreshers += {
            fun host(url: String, fallback: String) = Uri.parse(url.replace("{text}", "x")).host ?: fallback
            val lines = listOf(
                if (prefs.engine == Prefs.ENGINE_LENS) Triple(false, "Captured images → Google", "Google Lens OCR")
                else Triple(true, "OCR stays on this device", "ML Kit, offline"),
                when (prefs.dictionaryMode) {
                    Prefs.DICT_LOCAL -> Triple(true, "Lookups stay on this device", "Yomitan database")
                    Prefs.DICT_OFF -> Triple(true, "No lookups", "After capture: just copy")
                    else -> Triple(false, "Captured text → dictionary site", host(prefs.dictionaryUrl, "Your URL template"))
                },
                when {
                    prefs.translateHotkey == KeyEvent.KEYCODE_UNKNOWN ->
                        Triple(true, "No translations", "Translate hotkey not set")
                    prefs.deeplKey.isNotEmpty() -> Triple(false, "Translate text → DeepL", "Only when you press its hotkey")
                    else -> Triple(false, "Translate text → translator site", host(prefs.translateUrl, "Your URL template"))
                },
            )
            for ((row, line) in rows.zip(lines)) {
                val (local, name, sub) = line
                row.first.setImageResource(if (local) R.drawable.ic_lock else R.drawable.ic_cloud)
                tint(row.first, if (local) p.onVar else p.primary)
                row.second.text = name
                row.third.text = sub
            }
        }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        val about = row(Pos.SINGLE) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
        }
        about.addView(twoLine("OCR Clip $version", "GPL-3.0 · source on GitHub", titleSize = 15f, subSize = 12f).first,
            LinearLayout.LayoutParams(0, WRAP, 1f))
        about.addView(icon(R.drawable.ic_open, p.onVar), LinearLayout.LayoutParams(dpi(20), dpi(20)))
        right.section(null).card(about)
    }

    private fun serviceCard(on: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(16), dpi(16), dpi(16))
            background = shape(radii(Pos.SINGLE), if (on) p.card else p.ec)
        }
        val top = LinearLayout(this)
        top.addView(icon(if (on) R.drawable.ic_check_circle else R.drawable.ic_warning, if (on) p.primary else p.onEc),
            LinearLayout.LayoutParams(dpi(28), dpi(28)).apply { marginEnd = dpi(12) })
        val (texts, name, sub) = twoLine(
            if (on) "Running" else "Turned off",
            if (on) "The hotkeys work in every app."
            else "Turn on “${getString(R.string.service_label)}” so the hotkeys work.",
        )
        if (!on) {
            name.setTextColor(p.onEc)
            sub.setTextColor(p.onEc)
        }
        top.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(top)
        val open = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        card.addView(
            if (on) button("Accessibility settings", Style.OUTLINED, onClick = open) else button("Turn on in settings", Style.FILLED_ERROR, onClick = open),
            LinearLayout.LayoutParams(MATCH, dpi(44)).apply { topMargin = dpi(12) },
        )
        return card
    }

    // ---- Hotkey binding ----

    private fun keyName(code: Int) = Prefs.keyName(code).replace('_', ' ')

    /** Title, the bound key as a keycap (or "Not set"), an edit hint, and an optional clear button. */
    private fun keyCard(name: String, get: () -> Int, clear: (() -> Unit)?, bind: () -> Unit): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(radii(Pos.SINGLE), p.card)
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(10), dpi(8), dpi(10))
            minimumHeight = dpi(72)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = tappable(p, radiiAll(20), Color.TRANSPARENT)
            setOnClickListener { bind() }
        }
        main.addView(label(16f, p.on, value = name))
        val key = label(12f, p.on)
        main.addView(key, LinearLayout.LayoutParams(WRAP, dpi(26)).apply { topMargin = dpi(6) })
        card.addView(main, LinearLayout.LayoutParams(0, WRAP, 1f))
        val clearButton = clear?.let { c -> iconButton(R.drawable.ic_close, "Clear $name") { c() } }
        if (clearButton != null) {
            card.addView(clearButton, LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginEnd = dpi(4) })
        } else {
            card.addView(icon(R.drawable.ic_edit, p.onVar), LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginEnd = dpi(18) })
        }
        refreshers += {
            val code = get()
            val set = code != KeyEvent.KEYCODE_UNKNOWN
            key.gravity = Gravity.CENTER_VERTICAL
            if (set) {
                key.text = keyName(code)
                key.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                key.letterSpacing = 0.04f
                key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                key.setTextColor(p.on)
                key.setPadding(dpi(10), 0, dpi(10), dpi(2))
                key.background = keycap()
            } else {
                key.text = "Not set · tap to choose a button"
                key.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                key.letterSpacing = 0f
                key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                key.setTextColor(p.primary)
                key.setPadding(0, 0, 0, 0)
                key.background = null
            }
            clearButton?.visibility = if (set) View.VISIBLE else View.GONE
        }
        return card
    }

    /** A key cap: rounded, outlined, with a thicker bottom edge. */
    private fun keycap() = android.graphics.drawable.LayerDrawable(
        arrayOf(shape(radiiAll(8), p.outlineVar), shape(radiiAll(8), p.cardHigh, p.outlineVar, dpi(1))),
    ).apply { setLayerInset(1, 0, 0, 0, dpi(2)) }

    private fun startBinding(target: Binding) {
        bindDialog?.dismiss()
        binding = target
        OcrService.bindingHotkey = true

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(24), dpi(24), dpi(24), dpi(16))
            background = shape(radiiAll(28), p.cardHigh)
        }
        content.addView(icon(R.drawable.ic_gamepad, p.primary), LinearLayout.LayoutParams(dpi(28), dpi(28)))
        content.addView(label(24f, p.on, value = "Press a button").apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(16) })
        val body = if (target == Binding.LOOKUP) {
            "The next button you press becomes the lookup hotkey. Games won't see it while OCR Clip is active."
        } else {
            "The next button you press becomes the translate hotkey. It has to differ from the lookup hotkey."
        }
        content.addView(label(14f, p.onVar, value = body).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(16) })
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(p.primary)
        }
        val waiting = label(13f, p.onVar, value = leading(dot, 8, 10, "WAITING…")).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.04f
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setStroke(dpi(2), p.outline, dp(6f), dp(4f))
            }
        }
        content.addView(waiting, LinearLayout.LayoutParams(MATCH, dpi(52)).apply { topMargin = dpi(16) })
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(label(12f, p.onVar, value = "Back also cancels"), LinearLayout.LayoutParams(0, WRAP, 1f))
        val dialog = Dialog(this)
        actions.addView(button("Cancel", Style.TEXT) { dialog.dismiss() }, LinearLayout.LayoutParams(WRAP, dpi(44)))
        content.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(8) })

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Every key goes to the binding, D-pad and A included, before the dialog's own focus handling sees it.
        dialog.setOnKeyListener { _, code, event -> onBindingKey(code, event) }
        dialog.setOnDismissListener {
            if (bindDialog === dialog) {
                bindDialog = null
                stopBinding()
            }
        }
        bindDialog = dialog
        dialog.show()
        dialog.window?.setLayout(dpi(328), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun onBindingKey(code: Int, event: KeyEvent): Boolean {
        val target = binding ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        val other = if (target == Binding.LOOKUP) prefs.translateHotkey else prefs.hotkey
        when {
            code == KeyEvent.KEYCODE_BACK -> {}
            code == other ->
                Toast.makeText(this, "${Prefs.keyName(code)} is already the other hotkey", Toast.LENGTH_SHORT).show()
            target == Binding.LOOKUP -> prefs.hotkey = code
            else -> prefs.translateHotkey = code
        }
        bindDialog?.dismiss()
        return true
    }

    private fun stopBinding() {
        binding = null
        OcrService.bindingHotkey = false
    }

    private fun targetName(code: String) = DeepL.TARGETS.firstOrNull { it.first == code }?.let { "${it.second} · $code" } ?: code

    /** The DeepL target language as a list dialog; the D-pad starts on the current choice. */
    private fun pickTarget() {
        val current = prefs.deeplTarget
        val codes = DeepL.TARGETS.map { it.first }.let { if (current in it) it else listOf(current) + it }
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12), dpi(24), dpi(12), dpi(16))
            background = shape(radiiAll(28), p.cardHigh)
        }
        content.addView(label(24f, p.on, value = "Translate into").apply { setPadding(dpi(12), 0, dpi(12), 0) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var selected: View? = null
        for (code in codes) {
            val on = code == current
            val r = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpi(48)
                setPadding(dpi(12), 0, dpi(12), 0)
                isFocusable = true
                isClickable = true
                background = tappable(p, radiiAll(24), Color.TRANSPARENT)
                setOnClickListener {
                    prefs.deeplTarget = code
                    dialog.dismiss()
                }
            }
            r.announceAs("android.widget.RadioButton") { on }
            r.addView(Indicator(this, p, Indicator.Kind.RADIO).apply { checked = on },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dpi(16) })
            r.addView(label(16f, p.on, value = targetName(code)), LinearLayout.LayoutParams(0, WRAP, 1f))
            list.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            if (on) selected = r
        }
        val scroll = ScrollView(this).apply { addView(list) }
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dpi(16) })
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        actions.addView(button("Cancel", Style.TEXT) { dialog.dismiss() }, LinearLayout.LayoutParams(WRAP, dpi(44)))
        content.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(8) })

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(dpi(328), (resources.displayMetrics.heightPixels - dpi(48)).coerceAtMost(dpi(420)))
        selected?.let { s ->
            s.post {
                scroll.scrollTo(0, (s.top - dpi(96)).coerceAtLeast(0))
                s.requestFocusFromTouch() // also when opened by touch, so the D-pad starts here
            }
        }
    }

    // ---- Database import (unchanged) ----

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != PICK_DATABASE || resultCode != RESULT_OK || uri == null) return
        Toast.makeText(this, "Copying the dictionary database…", Toast.LENGTH_LONG).show()
        // A database with several dictionaries can be hundreds of MB, so copy it off the main thread.
        Thread {
            val target = LocalDictionary.file(this)
            val tmp = File(target.path + ".tmp")
            val failure = runCatching {
                contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                LocalDictionary.validate(tmp)
                if (!tmp.renameTo(target)) error("couldn't replace the old database")
            }.exceptionOrNull()
            tmp.delete()
            runOnUiThread {
                val message = failure?.let { "Couldn't load it: ${it.message}" } ?: "Dictionary database loaded"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                showDictionaries()
            }
        }.start()
    }

    // ---- Small builders ----

    private fun icon(res: Int, color: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = android.content.res.ColorStateList.valueOf(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun tint(view: ImageView, color: Int) {
        view.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun iconButton(res: Int, description: String, size: Int = 48, onClick: (View) -> Unit) =
        ImageButton(this).apply {
            setImageResource(res)
            imageTintList = android.content.res.ColorStateList.valueOf(p.onVar)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            background = tappable(p, radiiAll(size / 2), Color.TRANSPARENT)
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(dpi(size), dpi(size))
            setOnClickListener(onClick)
        }

    /** [text] after a [size] dp drawable and a [gap] dp space, kept as one centred run (compound drawables sit at the edge). */
    private fun leading(d: Drawable, size: Int, gap: Int, text: String): CharSequence {
        val inset = InsetDrawable(d, 0, 0, dpi(gap), 0).apply { setBounds(0, 0, dpi(size + gap), dpi(size)) }
        return SpannableString(" $text").apply {
            setSpan(ImageSpan(inset, DynamicDrawableSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun button(text: String, style: Style, iconRes: Int? = null, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dpi(16), 0, dpi(16), 0)
        gravity = Gravity.CENTER
        val r = radiiAll(22)
        when (style) {
            Style.TONAL -> {
                background = tappable(p, r, p.sc)
                setTextColor(p.onSc)
            }
            Style.OUTLINED -> {
                background = tappable(p, r, Color.TRANSPARENT, p.outline, dpi(1))
                setTextColor(p.primary)
            }
            Style.FILLED_ERROR -> {
                background = tappable(p, r, p.error)
                setTextColor(p.onError)
            }
            Style.TEXT -> {
                background = tappable(p, r, Color.TRANSPARENT)
                setTextColor(p.primary)
            }
        }
        if (iconRes != null) {
            this.text = leading(getDrawable(iconRes)!!.mutate().apply { setTint(currentTextColor) }, 18, 8, text)
            // The icon takes the room of the side padding, so a half-width button still fits on one line.
            setPadding(dpi(8), 0, dpi(8), 0)
            isSingleLine = true
        }
        setOnClickListener { onClick() }
    }

    /** A card row; tappable when [onClick] is given. */
    private fun row(pos: Pos, minHeight: Int = 56, onClick: (() -> Unit)? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpi(minHeight)
        setPadding(dpi(16), dpi(8), dpi(14), dpi(8))
        if (onClick != null) {
            isFocusable = true
            isClickable = true
            background = tappable(p, radii(pos), p.card)
            setOnClickListener { onClick() }
        } else {
            background = shape(radii(pos), p.card)
        }
    }

    private fun twoLine(title: String, sub: String, titleSize: Float = 16f, subSize: Float = 13f): Triple<LinearLayout, TextView, TextView> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = label(titleSize, p.on, value = title)
        val s = label(subSize, p.onVar, value = sub)
        box.addView(t)
        box.addView(s)
        return Triple(box, t, s)
    }

    private fun switchRow(pos: Pos, title: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val toggle = Indicator(this, p, Indicator.Kind.SWITCH)
        val r = row(pos) { set(!get()) }
        r.announceAs("android.widget.Switch", get)
        r.addView(label(16f, p.on, value = title), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(toggle)
        refreshers += { toggle.checked = get() }
        return r
    }

    private fun helper(text: String) = label(12f, p.onVar, value = text).apply { setPadding(dpi(16), dpi(6), dpi(16), 0) }

    /** Material outlined box: [box] at 56 dp with [name] sitting on its top border. Returns the frame and the label. */
    private fun outlined(name: String, box: View): Pair<FrameLayout, TextView> {
        val frame = FrameLayout(this)
        val caption = label(12f, p.onVar, value = name).apply {
            setBackgroundColor(p.bg)
            setPadding(dpi(4), 0, dpi(4), 0)
            gravity = Gravity.CENTER_VERTICAL
            labelFor = box.id
        }
        frame.addView(box, FrameLayout.LayoutParams(MATCH, dpi(56)).apply { topMargin = dpi(8) })
        frame.addView(caption, FrameLayout.LayoutParams(WRAP, dpi(16), Gravity.TOP or Gravity.START).apply { marginStart = dpi(12) })
        return frame to caption
    }

    /** Material outlined text field. */
    private fun field(name: String, initial: String, variation: Int, mono: Boolean = false, onChange: (String) -> Unit): Pair<FrameLayout, EditText> {
        val edit = EditText(this).apply {
            id = View.generateViewId()
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or variation
            setText(initial)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(p.on)
            setHintTextColor(p.onVar)
            if (mono) typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), 0, dpi(16), 0)
            val idle = shape(radiiAll(6), Color.TRANSPARENT, p.outline, dpi(1))
            val focused = shape(radiiAll(6), Color.TRANSPARENT, p.primary, dpi(2))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), idle)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) = onChange(s.toString().trim())
            })
        }
        val (frame, caption) = outlined(name, edit)
        edit.setOnFocusChangeListener { _, has -> caption.setTextColor(if (has) p.primary else p.onVar) }
        return frame to edit
    }

    companion object {
        private const val PICK_DATABASE = 1
        private const val KEY_SECTION = "section"
        private const val REPO_URL = "https://github.com/aypro/ocrclip"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
