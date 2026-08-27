package com.example.markdownreader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.ceil

/** A single heading extracted from the Markdown source, plus where it ends up in the rendered text. */
private data class TocItem(
    val level: Int,
    val title: String,
    var renderedCharIndex: Int = -1
)

/**
 * A minimal, lightweight Markdown file reader.
 *
 * Flow:
 *  1. User taps "Open Markdown File" (or opens a .md file from another app).
 *  2. We read the file's text via the SAF ContentResolver (no storage
 *     permissions required).
 *  3. Markwon renders the Markdown into a TextView that supports both text
 *     selection and working links (see [LinkAwareTextView]).
 *
 * The last successfully opened file (and scroll position) is remembered
 * across app restarts, so reopening the app drops you back where you left
 * off instead of an empty state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvMarkdownContent: LinkAwareTextView
    private lateinit var tvEmptyState: TextView
    private lateinit var tvFileName: TextView
    private lateinit var tvMeta: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var fabToc: FloatingActionButton
    private lateinit var progressIndicator: View
    private lateinit var markwon: Markwon

    private var tocItems: List<TocItem> = emptyList()
    private var anchorMap: Map<String, TocItem> = emptyMap()
    private var currentFileUri: Uri? = null

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
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvFileName = findViewById(R.id.tvFileName)
        tvMeta = findViewById(R.id.tvMeta)
        scrollView = findViewById(R.id.scrollView)
        fabToc = findViewById(R.id.fabToc)
        progressIndicator = findViewById(R.id.progressIndicator)

        val btnOpenFile: MaterialButton = findViewById(R.id.btnOpenFile)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)

        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_theme -> {
                    showThemeMenu(toolbar)
                    true
                }
                R.id.action_reload -> {
                    reloadCurrentFile()
                    true
                }
                R.id.action_share -> {
                    shareCurrentFile()
                    true
                }
                else -> false
            }
        }

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

                // Routes every rendered link tap through handleLinkClick() instead of the
                // default behavior, which would blindly try to launch a browser -- including
                // for in-document "#anchor" table-of-contents links, which aren't real URLs.
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { _, link -> handleLinkClick(link) }
                }
            })
            .build()

        // Keep the reading-progress bar in sync as the user scrolls.
        scrollView.viewTreeObserver.addOnScrollChangedListener { updateReadingProgress() }

        btnOpenFile.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
        }

        when {
            // If the app was launched by tapping a .md file elsewhere (e.g. Files app).
            intent?.data != null -> loadMarkdownFile(intent.data!!)

            // If we're being recreated (e.g. the theme was just switched, or a rotation),
            // reopen whatever file was on screen at whatever scroll position it was at,
            // instead of dropping back to the empty state.
            savedInstanceState != null && savedInstanceState.containsKey(KEY_FILE_URI) -> {
                savedInstanceState.getParcelable<Uri>(KEY_FILE_URI)?.let { uri ->
                    loadMarkdownFile(uri, restoreScrollY = savedInstanceState.getInt(KEY_SCROLL_Y, 0))
                }
            }

            // Fresh process start with nothing else to go on: reopen the last file the
            // user had open, at the position they left it, if we still have access to it.
            else -> {
                prefs().getString(KEY_LAST_FILE_URI, null)?.let { lastUriString ->
                    loadMarkdownFile(
                        uri = Uri.parse(lastUriString),
                        restoreScrollY = prefs().getInt(KEY_LAST_SCROLL_Y, 0),
                        isAutoRestore = true
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentFileUri?.let {
            outState.putParcelable(KEY_FILE_URI, it)
            outState.putInt(KEY_SCROLL_Y, scrollView.scrollY)
        }
    }

    override fun onPause() {
        super.onPause()
        // Remember exactly where we were, so relaunching the app after it's been killed
        // in the background drops the user back at the same spot.
        if (currentFileUri != null) {
            prefs().edit().putInt(KEY_LAST_SCROLL_Y, scrollView.scrollY).apply()
        }
    }

    private fun loadMarkdownFile(uri: Uri, restoreScrollY: Int = 0, isAutoRestore: Boolean = false) {
        try {
            val markdownText = contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            } ?: run {
                Toast.makeText(this, "Could not open file", Toast.LENGTH_SHORT).show()
                if (isAutoRestore) clearLastFile()
                return
            }

            currentFileUri = uri
            tvFileName.text = queryFileName(uri)
            tvMeta.text = buildMetaText(markdownText)
            tvMeta.visibility = View.VISIBLE
            markwon.setMarkdown(tvMarkdownContent, markdownText)

            tvEmptyState.visibility = View.GONE
            tvMarkdownContent.visibility = View.VISIBLE

            tocItems = extractHeadings(markdownText)
            anchorMap = buildAnchorMap(tocItems)

            tryPersistUriPermission(uri)
            saveLastFile(uri)

            // Wait for the TextView's Layout to be ready before we can map
            // heading text -> line position, then show/hide the FAB accordingly
            // and land on the right scroll position.
            tvMarkdownContent.post {
                locateTocPositions(tvMarkdownContent.text)
                fabToc.visibility = if (tocItems.any { it.renderedCharIndex >= 0 }) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                scrollView.scrollTo(0, restoreScrollY)
                updateReadingProgress()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
            if (isAutoRestore) clearLastFile()
        }
    }

    private fun reloadCurrentFile() {
        val uri = currentFileUri
        if (uri == null) {
            Toast.makeText(this, getString(R.string.reload_no_file), Toast.LENGTH_SHORT).show()
            return
        }
        val keepScrollY = scrollView.scrollY
        loadMarkdownFile(uri, restoreScrollY = keepScrollY)
    }

    private fun shareCurrentFile() {
        val uri = currentFileUri
        if (uri == null) {
            Toast.makeText(this, getString(R.string.share_no_file), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_file_chooser_title)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.share_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Handles every link tap coming out of the rendered Markdown.
     *
     * GitHub-style Markdown is full of `#some-heading` table-of-contents links that point
     * *within* the same document, not to a real URL -- launching a browser with `#anchor`
     * as the address just fails silently. We resolve those in-app by matching against a
     * GitHub-style slug of each heading instead, and only fall back to an external
     * ACTION_VIEW intent for genuine URLs.
     */
    private fun handleLinkClick(link: String) {
        if (link.startsWith("#")) {
            val fragment = Uri.decode(link.removePrefix("#")).trim().lowercase()
            val target = anchorMap[fragment]
            if (target != null && target.renderedCharIndex >= 0) {
                scrollToHeading(target)
            } else {
                Toast.makeText(this, getString(R.string.link_section_not_found), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Mirrors Markwon's own default resolver: a bare "www.example.com" style link
        // with no scheme is assumed to be https.
        val parsed = Uri.parse(link)
        val uri = if (parsed.scheme.isNullOrEmpty()) parsed.buildUpon().scheme("https").build() else parsed

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.link_no_app), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.link_open_failed), Toast.LENGTH_SHORT).show()
        }
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
        return items
    }

    /**
     * Builds a `#anchor -> TocItem` map using GitHub's own heading-slug rules (lowercase,
     * spaces become hyphens, punctuation stripped, duplicates get a `-1`, `-2`... suffix),
     * so links copied straight out of a GitHub-flavored Markdown file resolve correctly.
     */
    private fun buildAnchorMap(items: List<TocItem>): Map<String, TocItem> {
        val counts = mutableMapOf<String, Int>()
        val map = mutableMapOf<String, TocItem>()
        for (item in items) {
            val base = githubSlug(item.title)
            if (base.isEmpty()) continue
            val count = counts.getOrDefault(base, 0)
            val slug = if (count == 0) base else "$base-$count"
            counts[base] = count + 1
            map[slug] = item
        }
        return map
    }

    private fun githubSlug(title: String): String {
        val clean = title.replace(Regex("[*_`~]"), "")
        val lower = clean.lowercase().trim()
        val stripped = lower.replace(Regex("[^\\p{L}\\p{N}\\s_-]"), "")
        return stripped.trim().replace(Regex("\\s+"), "-")
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
        val activeItem = currentHeading()
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_toc, null)
        val container = sheetView.findViewById<LinearLayout>(R.id.tocContainer)

        // Always offer a quick way back to the very top of the document.
        container.addView(buildTocRow(getString(R.string.toc_top), 0, isActive = false) {
            scrollView.smoothScrollTo(0, 0)
            dialog.dismiss()
        })

        if (validItems.isEmpty()) {
            val emptyLabel = TextView(this).apply {
                text = getString(R.string.toc_empty)
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(20), dp(12), dp(20), dp(20))
            }
            container.addView(emptyLabel)
        } else {
            for (item in validItems) {
                container.addView(buildTocRow(item.title, item.level, isActive = item === activeItem) {
                    scrollToHeading(item)
                    dialog.dismiss()
                })
            }
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    /** Builds one tappable row for the TOC bottom sheet, indented to reflect heading depth. */
    private fun buildTocRow(title: String, level: Int, isActive: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = if (isActive) "${getString(R.string.toc_current_section)}: $title" else title
            setTextColor(getColor(if (isActive) R.color.purple_500 else R.color.text_primary))
            setTypeface(typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            textSize = if (level <= 1) 17f else 15f
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
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

    /** The heading the user is currently scrolled to, i.e. the last one above the viewport's top. */
    private fun currentHeading(): TocItem? {
        val layout = tvMarkdownContent.layout ?: return null
        val validItems = tocItems.filter { it.renderedCharIndex >= 0 }
        if (validItems.isEmpty()) return null

        val viewportTop = scrollView.scrollY - tvMarkdownContent.top - tvMarkdownContent.paddingTop + dp(24)
        var current: TocItem? = null
        for (item in validItems) {
            val line = layout.getLineForOffset(item.renderedCharIndex)
            if (layout.getLineTop(line) <= viewportTop) {
                current = item
            } else {
                break
            }
        }
        return current
    }

    /** Scrolls the main ScrollView so the given heading sits near the top of the screen. */
    private fun scrollToHeading(item: TocItem) {
        val layout = tvMarkdownContent.layout ?: return
        if (item.renderedCharIndex < 0 || item.renderedCharIndex >= tvMarkdownContent.text.length) return
        val line = layout.getLineForOffset(item.renderedCharIndex)
        val y = tvMarkdownContent.top + tvMarkdownContent.paddingTop + layout.getLineTop(line)
        scrollView.smoothScrollTo(0, (y - dp(16)).coerceAtLeast(0))
    }

    /** Updates the thin progress track under the header to reflect how far through the document we are. */
    private fun updateReadingProgress() {
        val track = progressIndicator.parent as? View ?: return
        val trackWidth = track.width
        if (tvMarkdownContent.visibility != View.VISIBLE || trackWidth <= 0) {
            progressIndicator.layoutParams = progressIndicator.layoutParams.apply { width = 0 }
            progressIndicator.requestLayout()
            return
        }
        val scrollContent = scrollView.getChildAt(0)
        val maxScroll = ((scrollContent?.height ?: 0) - scrollView.height).coerceAtLeast(0)
        val fraction = if (maxScroll == 0) 1f else (scrollView.scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        progressIndicator.layoutParams = progressIndicator.layoutParams.apply {
            width = (trackWidth * fraction).toInt()
        }
        progressIndicator.requestLayout()
    }

    /** A rough word count and estimated reading time (200 words/min) shown under the filename. */
    private fun buildMetaText(markdown: String): String {
        val words = markdown.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val minutes = ceil(words / 200.0).toInt().coerceAtLeast(1)
        return getString(R.string.meta_format, words, minutes)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // --- Last-opened-file persistence --------------------------------------

    /**
     * SAF only guarantees read access for as long as the picker's grant lasts unless we
     * explicitly ask to keep it, so this is what lets us reopen the same file after the
     * app (and its process) has been fully closed and relaunched.
     */
    private fun tryPersistUriPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Some providers (e.g. a one-off "Open with" from another app) don't grant a
            // persistable permission. The file still works for this session; it just won't
            // be available to auto-reopen on the next launch.
        }
    }

    private fun saveLastFile(uri: Uri) {
        prefs().edit().putString(KEY_LAST_FILE_URI, uri.toString()).apply()
    }

    private fun clearLastFile() {
        prefs().edit().remove(KEY_LAST_FILE_URI).remove(KEY_LAST_SCROLL_Y).apply()
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
        private const val KEY_SCROLL_Y = "current_scroll_y"
        private const val KEY_LAST_FILE_URI = "last_file_uri"
        private const val KEY_LAST_SCROLL_Y = "last_scroll_y"
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
