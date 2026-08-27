package com.example.markdownreader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** A single heading extracted from the Markdown source, plus where it ends up in the rendered text. */
private data class TocItem(
    val level: Int,
    val title: String,
    var renderedCharIndex: Int = -1,
    var slug: String = ""
)

/** A previously-opened file, remembered so the user can jump back into it without re-browsing. */
private data class RecentFile(
    val uriString: String,
    val name: String,
    val lastOpenedAt: Long
)

/**
 * A minimal, lightweight Markdown file reader.
 *
 * Flow:
 *  1. User taps "Open Markdown File" (or opens a .md file from another app).
 *  2. We read the file's text via the SAF ContentResolver (no storage
 *     permissions required).
 *  3. Markwon renders the Markdown into a plain TextView.
 *
 * On top of that core flow, the app remembers the last file opened (and a
 * short history of recent files) so it re-opens where you left off, restores
 * your reading position per file, and offers in-document search, adjustable
 * text size, reload, and share.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvMarkdownContent: TextView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var tvEmptyState: TextView
    private lateinit var llRecentFiles: LinearLayout
    private lateinit var tvFileName: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var fabToc: FloatingActionButton
    private lateinit var progressReading: ProgressBar
    private lateinit var searchBar: View
    private lateinit var etSearch: EditText
    private lateinit var tvSearchCount: TextView
    private lateinit var markwon: Markwon

    private var tocItems: List<TocItem> = emptyList()
    private var currentFileUri: Uri? = null

    private var searchMatches: List<Int> = emptyList()
    private var currentMatchIndex: Int = -1
    private val activeHighlightSpans = mutableListOf<Any>()

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadMarkdownFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must happen before setContentView so the correct theme resources are resolved.
        AppCompatDelegate.setDefaultNightMode(getSavedNightMode())

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMarkdownContent = findViewById(R.id.tvMarkdownContent)
        emptyStateContainer = findViewById(R.id.emptyStateContainer)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        llRecentFiles = findViewById(R.id.llRecentFiles)
        tvFileName = findViewById(R.id.tvFileName)
        scrollView = findViewById(R.id.scrollView)
        fabToc = findViewById(R.id.fabToc)
        progressReading = findViewById(R.id.progressReading)
        searchBar = findViewById(R.id.searchBar)
        etSearch = findViewById(R.id.etSearch)
        tvSearchCount = findViewById(R.id.tvSearchCount)

        val btnOpenFile: MaterialButton = findViewById(R.id.btnOpenFile)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)

        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item -> onMenuItemSelected(item.itemId, toolbar) }

        fabToc.setOnClickListener { showTocSheet() }

        markwon = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                // Keeps inline code / code blocks / quote bars legible in both light and dark mode
                // by pulling colors from the resources, which resolve to the values-night/ set
                // automatically when the app is in dark mode.
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.code_background))
                        .codeTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                        .blockQuoteColor(ContextCompat.getColor(this@MainActivity, R.color.purple_500))
                }

                // FIX: Markdown TOCs (like the one in this test file) link to headings with
                // "#some-heading" anchors. Markwon's default link handling just does
                // startActivity(ACTION_VIEW, Uri.parse(link)) for every link, and a scheme-less
                // "#anchor" Uri has no app that can handle it — so without this override, tapping
                // a TOC entry would crash with ActivityNotFoundException. Anchors now scroll to
                // the matching heading in-app; everything else still opens externally, safely.
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver(LinkResolver { _, link -> handleLinkClick(link) })
                }
            })
            .build()

        // FIX: android:textIsSelectable="true" (needed so users can long-press-select and copy
        // text) makes the TextView reset its movement method to one that only handles caret
        // movement, silently swallowing taps on links. Explicitly installing LinkMovementMethod
        // here restores link clicks while keeping text selection working.
        tvMarkdownContent.movementMethod = LinkMovementMethod.getInstance()

        applyFontScale(getSavedFontScale())

        btnOpenFile.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
        }

        setupSearchBar()

        // Keep the thin reading-progress bar in sync as the user scrolls.
        scrollView.viewTreeObserver.addOnScrollChangedListener { updateReadingProgress() }

        // Decide what to show on launch: a file we're being recreated with (e.g. after a theme
        // switch) wins, then a file we were opened with (e.g. "Open with -> Markdown Reader"),
        // and finally whatever the user had open last time, restored automatically.
        val restoredUri = savedInstanceState?.getParcelable<Uri>(KEY_FILE_URI)
        when {
            restoredUri != null -> loadMarkdownFile(restoredUri, silent = true)
            intent?.data != null -> loadMarkdownFile(intent.data!!)
            else -> restoreLastSessionFile()
        }

        renderRecentFilesInEmptyState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Reached when the app is already running and the user opens another .md file from
        // outside it (e.g. a file manager) — android:launchMode="singleTop" routes it here
        // instead of spawning a second instance.
        intent.data?.let { loadMarkdownFile(it) }
    }

    override fun onPause() {
        super.onPause()
        persistCurrentScrollPosition()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentFileUri?.let { outState.putParcelable(KEY_FILE_URI, it) }
    }

    private fun onMenuItemSelected(itemId: Int, toolbarAnchor: View): Boolean {
        return when (itemId) {
            R.id.action_theme -> {
                showThemeMenu(toolbarAnchor)
                true
            }
            R.id.action_search -> {
                toggleSearchBar(searchBar.visibility != View.VISIBLE)
                true
            }
            R.id.action_recent -> {
                showRecentFilesSheet()
                true
            }
            R.id.action_reload -> {
                currentFileUri?.let { loadMarkdownFile(it) }
                    ?: Toast.makeText(this, getString(R.string.no_file_open), Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_share -> {
                shareCurrentFile()
                true
            }
            R.id.action_font_increase -> {
                adjustFontScale(FONT_STEP)
                true
            }
            R.id.action_font_decrease -> {
                adjustFontScale(-FONT_STEP)
                true
            }
            R.id.action_font_reset -> {
                setSavedFontScale(1.0f)
                applyFontScale(1.0f)
                true
            }
            else -> false
        }
    }

    // --- Opening / loading files -------------------------------------------------

    /**
     * Loads a Markdown file into the reader.
     *
     * @param silent suppresses the error Toast (used when we're restoring state rather than
     *   responding to a direct user action, so a missing file doesn't surprise the user).
     * @param staleOnFailure means a failure should also forget this file (drop it from "last
     *   opened" / the recent-files list), used when we're the ones who suggested reopening it.
     */
    private fun loadMarkdownFile(uri: Uri, silent: Boolean = false, staleOnFailure: Boolean = false) {
        try {
            val markdownText = contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            } ?: run {
                if (!silent) Toast.makeText(this, "Could not open file", Toast.LENGTH_SHORT).show()
                return
            }

            currentFileUri = uri
            val displayName = queryFileName(uri)
            tvFileName.text = displayName
            markwon.setMarkdown(tvMarkdownContent, markdownText)
            // Reassert after every render, since a fresh setText is the moment this is most
            // likely to matter.
            tvMarkdownContent.movementMethod = LinkMovementMethod.getInstance()

            showContentState()
            clearSearch()

            tocItems = extractHeadings(markdownText)

            // Wait for the TextView's Layout to be ready before we can map
            // heading text -> line position, then show/hide the FAB accordingly.
            tvMarkdownContent.post {
                locateTocPositions(tvMarkdownContent.text)
                fabToc.visibility = if (tocItems.any { it.renderedCharIndex >= 0 }) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                restoreScrollPosition(uri)
                updateReadingProgress()
            }

            takePersistablePermission(uri)
            saveLastOpenedUri(uri)
            rememberAsRecent(uri, displayName)
        } catch (e: Exception) {
            if (staleOnFailure) {
                clearLastOpenedUriIfMatches(uri)
                removeRecent(uri.toString())
            }
            if (!silent) {
                Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
            }
            if (tvMarkdownContent.visibility != View.VISIBLE) {
                renderRecentFilesInEmptyState()
            }
        }
    }

    private fun showContentState() {
        emptyStateContainer.visibility = View.GONE
        tvMarkdownContent.visibility = View.VISIBLE
    }

    /** Pulls `# Heading` style lines out of the raw Markdown source, skipping fenced code blocks. */
    private fun extractHeadings(markdown: String): List<TocItem> {
        val headingLine = Regex("""^(#{1,6})\s+(.+?)\s*#*$""")
        val items = mutableListOf<TocItem>()
        var inFence = false

        for (rawLine in markdown.lineSequence()) {
            val line = rawLine.trimStart()
            if (line.startsWith("```") || line.startsWith("~~~")) {
                inFence = !inFence
                continue
            }
            if (inFence) continue

            val match = headingLine.find(line) ?: continue
            val level = match.groupValues[1].length
            val title = match.groupValues[2].trim()
            if (title.isNotEmpty()) {
                items.add(TocItem(level, title))
            }
        }
        assignSlugs(items)
        return items
    }

    /**
     * Reproduces GitHub's heading-anchor algorithm closely enough to match real Markdown files:
     * lowercase, drop anything that isn't a letter/digit/underscore/hyphen/space, turn each space
     * into a hyphen, and disambiguate repeated headings with a "-1", "-2", ... suffix.
     */
    private fun githubSlug(title: String): String {
        val sb = StringBuilder()
        for (c in title.lowercase()) {
            when {
                c.isLetterOrDigit() || c == '_' || c == '-' -> sb.append(c)
                c.isWhitespace() -> sb.append('-')
                else -> Unit // drop punctuation/emoji, matching GitHub's slugifier
            }
        }
        return sb.toString()
    }

    private fun assignSlugs(items: MutableList<TocItem>) {
        val seen = mutableMapOf<String, Int>()
        for (item in items) {
            val base = githubSlug(item.title)
            val count = seen.getOrDefault(base, 0)
            item.slug = if (count == 0) base else "$base-$count"
            seen[base] = count + 1
        }
    }

    /** Routes a tapped Markdown link: in-document "#anchor" links scroll to the heading; anything
     *  else is handed to an external app, defensively (a broken/unsupported link shouldn't crash
     *  the reader). */
    private fun handleLinkClick(link: String) {
        if (link.startsWith("#")) {
            val slug = Uri.decode(link.removePrefix("#"))
            if (slug.isBlank()) {
                scrollView.smoothScrollTo(0, 0)
                return
            }
            val target = tocItems.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
            if (target != null && target.renderedCharIndex >= 0) {
                scrollToHeading(target)
            } else {
                Toast.makeText(this, R.string.link_section_not_found, Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            val uri = Uri.parse(link)
            if (uri.scheme == null) {
                Toast.makeText(this, R.string.link_cannot_open, Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.link_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Markwon strips heading markup (#, **, etc.) when it renders, so we find each heading's
     * position in the final rendered text by searching for its cleaned-up title, in document
     * order, so repeated titles resolve to the correct instance.
     */
    private fun locateTocPositions(renderedText: CharSequence) {
        val rendered = renderedText.toString()
        var searchFrom = 0
        for (item in tocItems) {
            val cleanTitle = item.title.replace(Regex("[*_`~]"), "").trim()
            if (cleanTitle.isEmpty()) continue
            val idx = rendered.indexOf(cleanTitle, searchFrom)
            if (idx >= 0) {
                item.renderedCharIndex = idx
                searchFrom = idx + cleanTitle.length
            }
        }
    }

    /** Shows a bottom sheet listing every heading in the document; tapping one scrolls to it. */
    private fun showTocSheet() {
        val validItems = tocItems.filter { it.renderedCharIndex >= 0 }
        val currentItem = currentTocItem(validItems)
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_toc, null)
        sheetView.findViewById<TextView>(R.id.sheetTitle).text = getString(R.string.toc_title)
        val container = sheetView.findViewById<LinearLayout>(R.id.tocContainer)
        val filter = sheetView.findViewById<EditText>(R.id.etSheetFilter)

        fun rebuild(query: String) {
            container.removeAllViews()
            container.addView(buildTocRow(getString(R.string.toc_top), 0, isCurrent = currentItem == null) {
                scrollView.smoothScrollTo(0, 0)
                dialog.dismiss()
            })

            val filtered = validItems.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            if (filtered.isEmpty()) {
                if (query.isNotBlank()) {
                    container.addView(TextView(this).apply {
                        text = getString(R.string.toc_empty)
                        setTextColor(getColor(R.color.text_secondary))
                        setPadding(dp(20), dp(12), dp(20), dp(20))
                    })
                }
            } else {
                for (item in filtered) {
                    container.addView(buildTocRow(item.title, item.level, isCurrent = item === currentItem) {
                        scrollToHeading(item)
                        dialog.dismiss()
                    })
                }
            }
        }

        // Only worth the extra control on longer documents.
        if (validItems.size > 6) {
            filter.visibility = View.VISIBLE
            filter.doAfterTextChanged { rebuild(it?.toString().orEmpty()) }
        }

        if (validItems.isEmpty()) {
            container.addView(buildTocRow(getString(R.string.toc_top), 0, isCurrent = true) {
                scrollView.smoothScrollTo(0, 0)
                dialog.dismiss()
            })
            container.addView(TextView(this).apply {
                text = getString(R.string.toc_empty)
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(20), dp(12), dp(20), dp(20))
            })
        } else {
            rebuild("")
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    /** Finds the heading the user is currently reading, based on scroll position. */
    private fun currentTocItem(items: List<TocItem>): TocItem? {
        val layout = tvMarkdownContent.layout ?: return null
        val topY = scrollView.scrollY - tvMarkdownContent.top - tvMarkdownContent.paddingTop
        if (topY < 0) return null
        val line = layout.getLineForVertical(topY)
        val offset = layout.getLineStart(line)
        return items.lastOrNull { it.renderedCharIndex <= offset + 4 }
    }

    /** Builds one tappable row for the TOC bottom sheet, indented to reflect heading depth. */
    private fun buildTocRow(title: String, level: Int, isCurrent: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = if (isCurrent) "▸ $title" else title
            setTextColor(getColor(if (isCurrent) R.color.purple_500 else R.color.text_primary))
            setTypeface(typeface, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            textSize = if (level <= 1) 17f else 15f
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            val indent = dp(20 + (level.coerceAtLeast(1) - 1) * 16)
            setPadding(indent, dp(12), dp(20), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }
    }

    /** Scrolls the main ScrollView so the given heading sits near the top of the screen. */
    private fun scrollToHeading(item: TocItem) {
        val layout = tvMarkdownContent.layout ?: return
        if (item.renderedCharIndex < 0 || item.renderedCharIndex >= tvMarkdownContent.text.length) return
        val line = layout.getLineForOffset(item.renderedCharIndex)
        val y = tvMarkdownContent.top + tvMarkdownContent.paddingTop + layout.getLineTop(line)
        scrollView.smoothScrollTo(0, (y - dp(16)).coerceAtLeast(0))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // --- Reading progress ---------------------------------------------------

    private fun updateReadingProgress() {
        if (tvMarkdownContent.visibility != View.VISIBLE) {
            progressReading.visibility = View.GONE
            return
        }
        progressReading.visibility = View.VISIBLE
        val content = scrollView.getChildAt(0)
        val maxScroll = (content?.height ?: 0) - scrollView.height
        val progress = if (maxScroll <= 0) 100 else
            ((scrollView.scrollY.toFloat() / maxScroll) * 100f).toInt().coerceIn(0, 100)
        progressReading.progress = progress
    }

    // --- Text size ------------------------------------------------------------

    private fun getSavedFontScale(): Float = prefs().getFloat(KEY_FONT_SCALE, 1.0f)

    private fun setSavedFontScale(scale: Float) {
        prefs().edit().putFloat(KEY_FONT_SCALE, scale).apply()
    }

    private fun applyFontScale(scale: Float) {
        tvMarkdownContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * scale)
    }

    private fun adjustFontScale(delta: Float) {
        val newScale = (getSavedFontScale() + delta).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        setSavedFontScale(newScale)
        applyFontScale(newScale)
    }

    // --- In-document search -----------------------------------------------

    private fun setupSearchBar() {
        val btnClose: View = searchBar.findViewById(R.id.btnSearchClose)
        val btnPrev: View = searchBar.findViewById(R.id.btnSearchPrev)
        val btnNext: View = searchBar.findViewById(R.id.btnSearchNext)

        btnClose.setOnClickListener { toggleSearchBar(false) }
        btnPrev.setOnClickListener { jumpMatch(-1) }
        btnNext.setOnClickListener { jumpMatch(1) }
        etSearch.doAfterTextChanged { performSearch(it?.toString().orEmpty()) }
    }

    private fun toggleSearchBar(show: Boolean) {
        if (show) {
            searchBar.visibility = View.VISIBLE
            etSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        } else {
            clearSearch()
            searchBar.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        }
    }

    private fun performSearch(query: String) {
        clearHighlights()
        if (query.isBlank() || tvMarkdownContent.visibility != View.VISIBLE) {
            searchMatches = emptyList()
            currentMatchIndex = -1
            tvSearchCount.text = ""
            return
        }
        val text = tvMarkdownContent.text.toString()
        val matches = mutableListOf<Int>()
        var idx = text.indexOf(query, 0, ignoreCase = true)
        while (idx >= 0) {
            matches += idx
            idx = text.indexOf(query, idx + query.length, ignoreCase = true)
        }
        searchMatches = matches
        currentMatchIndex = if (matches.isEmpty()) -1 else 0
        highlightMatches(query.length)
        updateSearchCount()
        if (currentMatchIndex >= 0) scrollToTextOffset(searchMatches[currentMatchIndex])
    }

    private fun highlightMatches(matchLength: Int) {
        val spannable = tvMarkdownContent.text as? Spannable ?: return
        searchMatches.forEachIndexed { i, start ->
            val color = if (i == currentMatchIndex) R.color.search_highlight_current else R.color.search_highlight
            val span = BackgroundColorSpan(getColor(color))
            val end = (start + matchLength).coerceAtMost(spannable.length)
            spannable.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            activeHighlightSpans += span
        }
    }

    private fun clearHighlights() {
        val spannable = tvMarkdownContent.text as? Spannable
        activeHighlightSpans.forEach { spannable?.removeSpan(it) }
        activeHighlightSpans.clear()
    }

    private fun clearSearch() {
        etSearch.setText("")
        clearHighlights()
        searchMatches = emptyList()
        currentMatchIndex = -1
        tvSearchCount.text = ""
    }

    private fun jumpMatch(direction: Int) {
        if (searchMatches.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + direction + searchMatches.size) % searchMatches.size
        clearHighlights()
        highlightMatches(etSearch.text.length)
        updateSearchCount()
        scrollToTextOffset(searchMatches[currentMatchIndex])
    }

    private fun updateSearchCount() {
        tvSearchCount.text = if (searchMatches.isEmpty()) {
            getString(R.string.search_no_matches)
        } else {
            getString(R.string.search_match_count, currentMatchIndex + 1, searchMatches.size)
        }
    }

    private fun scrollToTextOffset(offset: Int) {
        val layout = tvMarkdownContent.layout ?: return
        val line = layout.getLineForOffset(offset)
        val y = tvMarkdownContent.top + tvMarkdownContent.paddingTop + layout.getLineTop(line)
        scrollView.smoothScrollTo(0, (y - dp(80)).coerceAtLeast(0))
    }

    // --- Remembering the last file & per-file scroll position ---------------------

    private fun restoreLastSessionFile() {
        val last = prefs().getString(KEY_LAST_FILE_URI, null) ?: return
        loadMarkdownFile(Uri.parse(last), silent = true, staleOnFailure = true)
    }

    private fun takePersistablePermission(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Some providers don't support persistable grants; the file will just need to be
            // re-opened manually next launch in that case, which is an acceptable fallback.
        }
    }

    private fun saveLastOpenedUri(uri: Uri) {
        prefs().edit().putString(KEY_LAST_FILE_URI, uri.toString()).apply()
    }

    private fun clearLastOpenedUriIfMatches(uri: Uri) {
        if (prefs().getString(KEY_LAST_FILE_URI, null) == uri.toString()) {
            prefs().edit().remove(KEY_LAST_FILE_URI).apply()
        }
    }

    private fun scrollKey(uri: Uri) = "scroll_pos:$uri"

    private fun persistCurrentScrollPosition() {
        currentFileUri?.let { prefs().edit().putInt(scrollKey(it), scrollView.scrollY).apply() }
    }

    private fun restoreScrollPosition(uri: Uri) {
        val saved = prefs().getInt(scrollKey(uri), 0)
        scrollView.post { scrollView.scrollTo(0, saved) }
    }

    // --- Recent files -------------------------------------------------------------

    private fun loadRecents(): MutableList<RecentFile> {
        val raw = prefs().getString(KEY_RECENTS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentFile(o.getString("uri"), o.getString("name"), o.optLong("time", 0L))
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveRecents(list: List<RecentFile>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("uri", r.uriString)
                put("name", r.name)
                put("time", r.lastOpenedAt)
            })
        }
        prefs().edit().putString(KEY_RECENTS, arr.toString()).apply()
    }

    private fun rememberAsRecent(uri: Uri, name: String) {
        val list = loadRecents()
        list.removeAll { it.uriString == uri.toString() }
        list.add(0, RecentFile(uri.toString(), name, System.currentTimeMillis()))
        while (list.size > MAX_RECENTS) list.removeAt(list.size - 1)
        saveRecents(list)
        if (tvMarkdownContent.visibility != View.VISIBLE) renderRecentFilesInEmptyState()
    }

    private fun removeRecent(uriString: String) {
        val list = loadRecents()
        list.removeAll { it.uriString == uriString }
        saveRecents(list)
        renderRecentFilesInEmptyState()
    }

    private fun openRecent(file: RecentFile) {
        loadMarkdownFile(Uri.parse(file.uriString), silent = false, staleOnFailure = true)
    }

    /** Shows a quick-access list of recent files under the empty state, when there's no doc open. */
    private fun renderRecentFilesInEmptyState() {
        llRecentFiles.removeAllViews()
        val recents = loadRecents()
        if (recents.isEmpty() || tvMarkdownContent.visibility == View.VISIBLE) {
            llRecentFiles.visibility = View.GONE
            return
        }
        llRecentFiles.visibility = View.VISIBLE
        llRecentFiles.addView(TextView(this).apply {
            text = getString(R.string.recent_files_title)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(dp(24), dp(4), dp(24), dp(4))
        })
        recents.forEach { r -> llRecentFiles.addView(buildRecentRow(r) { openRecent(r) }) }
    }

    /** Shows every recent file in a bottom sheet (reachable any time from the overflow menu). */
    private fun showRecentFilesSheet() {
        val recents = loadRecents()
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_toc, null)
        sheetView.findViewById<TextView>(R.id.sheetTitle).text = getString(R.string.recent_files_title)
        sheetView.findViewById<View>(R.id.etSheetFilter).visibility = View.GONE
        val container = sheetView.findViewById<LinearLayout>(R.id.tocContainer)

        if (recents.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.recent_files_empty)
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(20), dp(12), dp(20), dp(20))
            })
        } else {
            recents.forEach { r ->
                container.addView(buildRecentRow(r) {
                    dialog.dismiss()
                    openRecent(r)
                })
            }
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    /** Builds one row: file name + "opened X ago", with a small button to forget it. */
    private fun buildRecentRow(file: RecentFile, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setPadding(dp(24), dp(10), dp(16), dp(10))
            setOnClickListener { onClick() }
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = file.name
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        texts.addView(TextView(this).apply {
            text = DateUtils.getRelativeTimeSpanString(file.lastOpenedAt)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        })
        row.addView(texts)

        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(getColor(R.color.text_secondary))
            contentDescription = getString(R.string.recent_remove_description)
            val sizePx = dp(36)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply { marginStart = dp(8) }
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { removeRecent(file.uriString) }
        })

        return row
    }

    // --- Sharing --------------------------------------------------------------

    private fun shareCurrentFile() {
        val uri = currentFileUri
        if (uri == null) {
            Toast.makeText(this, getString(R.string.no_file_open), Toast.LENGTH_SHORT).show()
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
    }

    // --- Day / night mode -------------------------------------------------

    private fun prefs(): SharedPreferences =
        getSharedPreferences("markdown_reader_prefs", Context.MODE_PRIVATE)

    private fun getSavedNightMode(): Int =
        prefs().getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    private fun setSavedNightMode(mode: Int) {
        prefs().edit().putInt(KEY_NIGHT_MODE, mode).apply()
    }

    /** Popup letting the user pick System default / Light / Dark, applied immediately. */
    private fun showThemeMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, getString(R.string.theme_option_system))
        popup.menu.add(0, 1, 1, getString(R.string.theme_option_light))
        popup.menu.add(0, 2, 2, getString(R.string.theme_option_dark))

        popup.setOnMenuItemClickListener { item ->
            val newMode = when (item.itemId) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (newMode != getSavedNightMode()) {
                setSavedNightMode(newMode)
                AppCompatDelegate.setDefaultNightMode(newMode)
                recreate()
            }
            true
        }
        popup.show()
    }

    companion object {
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_FILE_URI = "current_file_uri"
        private const val KEY_LAST_FILE_URI = "last_file_uri"
        private const val KEY_RECENTS = "recent_files_json"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val MAX_RECENTS = 8
        private const val BASE_TEXT_SIZE_SP = 16f
        private const val FONT_STEP = 0.1f
        private const val MIN_FONT_SCALE = 0.8f
        private const val MAX_FONT_SCALE = 1.6f
    }

    /** Looks up the display name of a content:// or file:// Uri, falling back to its last path segment. */
    private fun queryFileName(uri: Uri): String {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment ?: "Untitled.md"
    }
}
