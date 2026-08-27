package com.example.markdownreader

import android.content.Context
import android.content.SharedPreferences
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
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.io.BufferedReader
import java.io.InputStreamReader

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
 *  3. Markwon renders the Markdown into a plain TextView.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvMarkdownContent: TextView
    private lateinit var tvEmptyState: TextView
    private lateinit var tvFileName: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var fabToc: FloatingActionButton
    private lateinit var markwon: Markwon

    private var tocItems: List<TocItem> = emptyList()
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
        scrollView = findViewById(R.id.scrollView)
        fabToc = findViewById(R.id.fabToc)

        val btnOpenFile: MaterialButton = findViewById(R.id.btnOpenFile)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)

        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_theme) {
                showThemeMenu(toolbar)
                true
            } else {
                false
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
            })
            .build()

        btnOpenFile.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
        }

        // If the app was launched by tapping a .md file elsewhere (e.g. Files app)
        intent?.data?.let { uri -> loadMarkdownFile(uri) }

        // If we're being recreated (e.g. the theme was just switched), reopen whatever
        // file was on screen instead of dropping back to the empty state.
        savedInstanceState?.getParcelable<Uri>(KEY_FILE_URI)?.let { uri -> loadMarkdownFile(uri) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentFileUri?.let { outState.putParcelable(KEY_FILE_URI, it) }
    }

    private fun loadMarkdownFile(uri: Uri) {
        try {
            val markdownText = contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            } ?: run {
                Toast.makeText(this, "Could not open file", Toast.LENGTH_SHORT).show()
                return
            }

            currentFileUri = uri
            tvFileName.text = queryFileName(uri)
            markwon.setMarkdown(tvMarkdownContent, markdownText)

            tvEmptyState.visibility = View.GONE
            tvMarkdownContent.visibility = View.VISIBLE
            scrollView.post { scrollView.scrollTo(0, 0) }

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
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
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
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_toc, null)
        val container = sheetView.findViewById<LinearLayout>(R.id.tocContainer)

        // Always offer a quick way back to the very top of the document.
        container.addView(buildTocRow(getString(R.string.toc_top), 0) {
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
                container.addView(buildTocRow(item.title, item.level) {
                    scrollToHeading(item)
                    dialog.dismiss()
                })
            }
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    /** Builds one tappable row for the TOC bottom sheet, indented to reflect heading depth. */
    private fun buildTocRow(title: String, level: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
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
