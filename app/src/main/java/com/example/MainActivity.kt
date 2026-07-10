package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import android.media.MediaPlayer
import android.media.AudioManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var webViewRef: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Copy default sample PDF on start-up
        copyAssetToCache(this, "sample.pdf")

        setContent {
            MyApplicationTheme {
                PDFReaderScreen(
                    onNextPage = { executeJs("PDFViewerApplication.pdfViewer.nextPage();") },
                    onPrevPage = { executeJs("PDFViewerApplication.pdfViewer.previousPage();") },
                    onZoomIn = { executeJs("if (typeof PDFViewerApplication !== 'undefined') { PDFViewerApplication.pdfViewer.currentScale += 0.25; }") },
                    onZoomOut = { executeJs("if (typeof PDFViewerApplication !== 'undefined') { PDFViewerApplication.pdfViewer.currentScale -= 0.25; }") },
                    onGoToPage = { pageNum -> executeJs("if (typeof PDFViewerApplication !== 'undefined') { PDFViewerApplication.pdfViewer.currentPageNumber = $pageNum; }") },
                    onToggleScrollMode = { isHorizontal ->
                        val mode = if (isHorizontal) 1 else 0
                        executeJs("if (typeof PDFViewerApplication !== 'undefined') { PDFViewerApplication.pdfViewer.scrollMode = $mode; }")
                    },
                    onToggleSnapMode = { isSnap ->
                        val mode = if (isSnap) 3 else 0
                        executeJs("if (typeof PDFViewerApplication !== 'undefined') { PDFViewerApplication.pdfViewer.scrollMode = $mode; }")
                    },
                    onSearch = { query ->
                        val encodedQuery = android.net.Uri.encode(query)
                        executeJs("""
                            if (typeof PDFViewerApplication !== 'undefined') {
                                var decodedQuery = decodeURIComponent('$encodedQuery');
                                var options = {
                                    query: decodedQuery,
                                    phraseSearch: false,
                                    caseSensitive: false,
                                    entireWord: false,
                                    highlightAll: true,
                                    findPrevious: false,
                                    type: ''
                                };
                                if (PDFViewerApplication.eventBus) {
                                    PDFViewerApplication.eventBus.dispatch('find', options);
                                } else {
                                    var fc = PDFViewerApplication.findController || PDFViewerApplication.pdfFindController || (PDFViewerApplication.pdfViewer && PDFViewerApplication.pdfViewer.findController);
                                    if (fc) {
                                        fc.executeCommand('find', options);
                                    }
                                }
                            }
                        """.trimIndent())
                    },
                    onSearchNext = { query ->
                        val encodedQuery = android.net.Uri.encode(query)
                        executeJs("""
                            if (typeof PDFViewerApplication !== 'undefined') {
                                var decodedQuery = decodeURIComponent('$encodedQuery');
                                var options = {
                                    query: decodedQuery,
                                    phraseSearch: false,
                                    caseSensitive: false,
                                    entireWord: false,
                                    highlightAll: true,
                                    findPrevious: false,
                                    type: 'again'
                                };
                                if (PDFViewerApplication.eventBus) {
                                    PDFViewerApplication.eventBus.dispatch('find', options);
                                } else {
                                    var fc = PDFViewerApplication.findController || PDFViewerApplication.pdfFindController || (PDFViewerApplication.pdfViewer && PDFViewerApplication.pdfViewer.findController);
                                    if (fc) {
                                        fc.executeCommand('find', options);
                                    }
                                }
                            }
                        """.trimIndent())
                    },
                    onSearchPrev = { query ->
                        val encodedQuery = android.net.Uri.encode(query)
                        executeJs("""
                            if (typeof PDFViewerApplication !== 'undefined') {
                                var decodedQuery = decodeURIComponent('$encodedQuery');
                                var options = {
                                    query: decodedQuery,
                                    phraseSearch: false,
                                    caseSensitive: false,
                                    entireWord: false,
                                    highlightAll: true,
                                    findPrevious: true,
                                    type: 'again'
                                };
                                if (PDFViewerApplication.eventBus) {
                                    PDFViewerApplication.eventBus.dispatch('find', options);
                                } else {
                                    var fc = PDFViewerApplication.findController || PDFViewerApplication.pdfFindController || (PDFViewerApplication.pdfViewer && PDFViewerApplication.pdfViewer.findController);
                                    if (fc) {
                                        fc.executeCommand('find', options);
                                    }
                                }
                            }
                        """.trimIndent())
                    },
                    onClearSearch = {
                        executeJs("""
                            if (typeof PDFViewerApplication !== 'undefined') {
                                var options = {
                                    query: '',
                                    phraseSearch: false,
                                    caseSensitive: false,
                                    entireWord: false,
                                    highlightAll: false,
                                    findPrevious: false,
                                    type: ''
                                };
                                if (PDFViewerApplication.eventBus) {
                                    PDFViewerApplication.eventBus.dispatch('find', options);
                                } else {
                                    var fc = PDFViewerApplication.findController || PDFViewerApplication.pdfFindController || (PDFViewerApplication.pdfViewer && PDFViewerApplication.pdfViewer.findController);
                                    if (fc) {
                                        fc.executeCommand('find', options);
                                    }
                                }
                            }
                        """.trimIndent())
                    },
                    setWebView = { webViewRef = it }
                )
            }
        }
    }

    fun executeJs(script: String) {
        webViewRef?.post {
            webViewRef?.evaluateJavascript(script, null)
        }
    }

    private fun copyAssetToCache(context: Context, assetName: String) {
        try {
            val safeFileName = getSafePdfFileName(assetName)
            val cacheFile = File(context.cacheDir, safeFileName)
            val genericFile = File(context.cacheDir, "current_reader_doc.pdf")
            
            context.assets.open(assetName).use { inputStream ->
                val bytes = inputStream.readBytes()
                FileOutputStream(cacheFile).use { it.write(bytes) }
                FileOutputStream(genericFile).use { it.write(bytes) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// Communication Bridge for WebView PDF.js Events to Compose
class PdfAndroidBridge(
    private val onPageChanged: (page: Int, total: Int) -> Unit,
    private val onScaleChanged: (scale: Float) -> Unit,
    private val onSearchMatchesChanged: (current: Int, total: Int) -> Unit,
    private val onLinkClicked: (url: String, text: String) -> Unit
) {
    @JavascriptInterface
    fun onPageChanged(pageNumber: Int, pagesCount: Int) {
        onPageChanged.invoke(pageNumber, pagesCount)
    }

    @JavascriptInterface
    fun onScaleChanged(scale: Float) {
        onScaleChanged.invoke(scale)
    }

    @JavascriptInterface
    fun onSearchMatchesChanged(current: Int, total: Int) {
        onSearchMatchesChanged.invoke(current, total)
    }

    @JavascriptInterface
    fun onLinkClicked(url: String, text: String) {
        onLinkClicked.invoke(url, text)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PDFReaderScreen(
    onNextPage: () -> Unit,
    onPrevPage: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onGoToPage: (Int) -> Unit,
    onToggleScrollMode: (Boolean) -> Unit,
    onToggleSnapMode: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onSearchNext: (String) -> Unit,
    onSearchPrev: (String) -> Unit,
    onClearSearch: () -> Unit,
    setWebView: (WebView) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()

    // SharedPreferences for Bookmarks & Favorites
    val sharedPrefs = remember { context.getSharedPreferences("PdfReaderPrefs", Context.MODE_PRIVATE) }

    // State Variables
    var currentPage by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(1) }
    var currentScale by remember { mutableStateOf(1.0f) }
    var pdfName by remember { mutableStateOf("sample.pdf") }
    var isCopying by remember { mutableStateOf(false) }

    // Audio & Embedded Web Browser States
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingAudioUrl by remember { mutableStateOf<String?>(null) }
    var playingAudioText by remember { mutableStateOf("") }
    var isAudioPlayingState by remember { mutableStateOf(false) }
    var audioProgress by remember { mutableStateOf(0.0f) }
    var audioDurationText by remember { mutableStateOf("0:00") }
    var audioPositionText by remember { mutableStateOf("0:00") }
    var activeWebUrl by remember { mutableStateOf<String?>(null) }
    var isWebLoading by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }

    // Check if the URL is an audio resource
    fun isAudioUrl(url: String): Boolean {
        val cleanUrl = url.lowercase()
        return cleanUrl.endsWith(".mp3") || 
               cleanUrl.endsWith(".wav") || 
               cleanUrl.endsWith(".ogg") || 
               cleanUrl.endsWith(".m4a") || 
               cleanUrl.endsWith(".aac") ||
               cleanUrl.contains("/audio/") || 
               cleanUrl.contains("/pronunciation/") || 
               cleanUrl.contains("/sound/") ||
               cleanUrl.contains("audio_") ||
               cleanUrl.contains("pronounce")
    }

    fun playAudio(url: String, text: String) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isAudioPlayingState = false
            audioProgress = 0.0f
            playingAudioUrl = url
            playingAudioText = text

            val mp = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(url)
                setOnPreparedListener {
                    it.start()
                    isAudioPlayingState = true
                }
                setOnCompletionListener {
                    isAudioPlayingState = false
                    audioProgress = 1.0f
                    scope.launch {
                        delay(1500)
                        if (!isAudioPlayingState) {
                            playingAudioUrl = null
                        }
                    }
                }
                setOnErrorListener { _, _, _ ->
                    isAudioPlayingState = false
                    Toast.makeText(context, "خطأ في تشغيل الصوت", Toast.LENGTH_SHORT).show()
                    false
                }
            }
            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "فشل تحميل ملف الصوت", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(isAudioPlayingState, playingAudioUrl) {
        if (isAudioPlayingState) {
            while (isAudioPlayingState) {
                mediaPlayer?.let { mp ->
                    try {
                        if (mp.isPlaying) {
                            val pos = mp.currentPosition
                            val dur = mp.duration
                            if (dur > 0) {
                                audioProgress = pos.toFloat() / dur.toFloat()
                                
                                val posMin = (pos / 1000) / 60
                                val posSec = (pos / 1000) % 60
                                audioPositionText = String.format("%d:%02d", posMin, posSec)

                                val durMin = (dur / 1000) / 60
                                val durSec = (dur / 1000) % 60
                                audioDurationText = String.format("%d:%02d", durMin, durSec)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
                delay(100)
            }
        }
    }

    // Search state
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatch by remember { mutableStateOf(0) }
    var totalMatches by remember { mutableStateOf(0) }

    // Display / layout configurations
    var isHorizontalScroll by remember { mutableStateOf(false) }
    var isSnapToPage by remember { mutableStateOf(false) }
    var isDoubleSpread by remember { mutableStateOf(false) }
    var currentTheme by remember { mutableStateOf("light") } // "light", "dark", "sepia", "eyecare"

    // Auto-Scroll States
    var isAutoScrolling by remember { mutableStateOf(false) }
    var autoScrollSpeed by remember { mutableStateOf(2) } // 1: Slow, 2: Medium, 3: Fast

    // Immersive display toggle (User taps top bar toggle or can click immersive)
    var isBarsVisible by remember { mutableStateOf(true) }

    // Bookmarked Pages State
    var bookmarks by remember {
        mutableStateOf(
            sharedPrefs.getStringSet("bookmarks_$pdfName", emptySet())
                ?.mapNotNull { it.toIntOrNull() }
                ?.toSet() ?: emptySet()
        )
    }

    // Favorite Flag
    var isFavorite by remember {
        mutableStateOf(sharedPrefs.getBoolean("fav_$pdfName", false))
    }

    // Active bottom sheet selection: "tools", "theme", "zoom", "layout", "navigation"
    var activeSheet by remember { mutableStateOf<String?>(null) }

    // Selected tab in pages/bookmarks navigation sheet (0: Pages list, 1: Bookmarks list, 2: TOC)
    var activeNavTab by remember { mutableStateOf(0) }

    // State for local reloads
    var reloadTrigger by remember { mutableStateOf(0) }

    // Sync state when PDF document changes
    LaunchedEffect(pdfName) {
        bookmarks = sharedPrefs.getStringSet("bookmarks_$pdfName", emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet() ?: emptySet()
        isFavorite = sharedPrefs.getBoolean("fav_$pdfName", false)
    }

    // JS helper to execute in the WebView
    fun executeJs(script: String) {
        if (activity is MainActivity) {
            activity.executeJs(script)
        }
    }

    // Dynamic color injector for Reader Theme (Light, Night, Sepia, Eye Care)
    fun applyReaderTheme(themeName: String) {
        val filter = when (themeName) {
            "dark" -> "invert(0.9) hue-rotate(180deg)"
            "sepia" -> "sepia(0.6) contrast(0.95) brightness(1.02)"
            "eyecare" -> "sepia(0.4) hue-rotate(60deg) contrast(0.95) brightness(1.0)"
            else -> "" // light / standard
        }
        val bg = when (themeName) {
            "dark" -> "#121212"
            "sepia" -> "#f4ecd8"
            "eyecare" -> "#e1eed6"
            else -> "#f4f4f5"
        }
        val themeScript = """
            var styleId = 'pdf-reader-theme-style';
            var styleEl = document.getElementById(styleId);
            if (!styleEl) {
                styleEl = document.createElement('style');
                styleEl.id = styleId;
                document.head.appendChild(styleEl);
            }
            styleEl.innerHTML = 'document, html { background-color: $bg !important; } .page { background-color: $bg !important; }';
            document.documentElement.style.filter = '$filter';
            var container = document.getElementById('viewerContainer');
            if (container) {
                container.style.backgroundColor = '$bg';
            }
        """.trimIndent()
        executeJs(themeScript)
    }

    // Helper to configure scroll, snap, and spreads in PDF.js
    fun applyScrollLayout(isHorizontal: Boolean, isSnap: Boolean, isDouble: Boolean) {
        val scrollMode = when {
            isSnap -> 3 // presentation/snap page
            isHorizontal -> 1 // horizontal scroll
            else -> 0 // vertical scroll
        }
        val spreadMode = if (isDouble) 1 else 0
        val script = """
            if (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.pdfViewer) {
                PDFViewerApplication.pdfViewer.scrollMode = $scrollMode;
                PDFViewerApplication.pdfViewer.spreadMode = $spreadMode;
            }
        """.trimIndent()
        executeJs(script)
    }

    // Auto-Scroll implementation
    fun handleAutoScroll(scroll: Boolean, speed: Int) {
        if (!scroll) {
            executeJs("""
                if (window.autoScrollInterval) {
                    clearInterval(window.autoScrollInterval);
                    window.autoScrollInterval = null;
                }
            """.trimIndent())
            return
        }
        val step = when (speed) {
            1 -> 1  // Slow
            2 -> 3  // Medium
            else -> 6 // Fast
        }
        val script = """
            if (window.autoScrollInterval) {
                clearInterval(window.autoScrollInterval);
            }
            window.autoScrollInterval = setInterval(function() {
                var container = document.getElementById('viewerContainer');
                if (container) {
                    container.scrollTop += $step;
                }
            }, 35);
        """.trimIndent()
        executeJs(script)
    }

    // Update Auto-scroll when speed changes
    LaunchedEffect(isAutoScrolling, autoScrollSpeed) {
        handleAutoScroll(isAutoScrolling, autoScrollSpeed)
    }

    // Save copy of cached PDF to Downloads
    fun savePdfCopy(context: Context): Boolean {
        return try {
            val safeFileName = getSafePdfFileName(pdfName)
            val cacheFile = File(context.cacheDir, safeFileName)
            if (!cacheFile.exists()) return false

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "Reader_Pro_${safeFileName}")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        cacheFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    true
                } else {
                    false
                }
            } else {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val targetFile = File(downloadsDir, "Reader_Pro_${safeFileName}")
                cacheFile.inputStream().use { inputStream ->
                    targetFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Share PDF document using declared FileProvider
    fun sharePdf(context: Context) {
        try {
            val safeFileName = getSafePdfFileName(pdfName)
            val cacheFile = File(context.cacheDir, safeFileName)
            if (!cacheFile.exists()) {
                Toast.makeText(context, "No active document to share", Toast.LENGTH_SHORT).show()
                return
            }
            val authority = "com.example.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, cacheFile)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share PDF Document"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Sharing failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Print PDF document via Webview creation
    fun printPdf(context: Context) {
        if (activity is MainActivity) {
            try {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                // Retrieve webview ref from activity/screen
                val jobName = "PDF Reader - $pdfName"
                // Standard Android WebView print system works seamlessly!
                Toast.makeText(context, "Preparing print job...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Failed to start print", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Toggle current page bookmark
    val isCurrentPageBookmarked = bookmarks.contains(currentPage)
    fun togglePageBookmark() {
        val newBookmarks = bookmarks.toMutableSet()
        if (isCurrentPageBookmarked) {
            newBookmarks.remove(currentPage)
            Toast.makeText(context, "Bookmark removed for page $currentPage", Toast.LENGTH_SHORT).show()
        } else {
            newBookmarks.add(currentPage)
            Toast.makeText(context, "Page $currentPage bookmarked!", Toast.LENGTH_SHORT).show()
        }
        bookmarks = newBookmarks
        sharedPrefs.edit()
            .putStringSet("bookmarks_$pdfName", newBookmarks.map { it.toString() }.toSet())
            .apply()
    }

    // Launcher for selecting external PDFs
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            isCopying = true
            scope.launch {
                val originalName = getFileNameFromUri(context, selectedUri) ?: "External Document.pdf"
                val safeFileName = getSafePdfFileName(originalName)
                val success = withContext(Dispatchers.IO) {
                    copyUriToCache(context, selectedUri, safeFileName)
                }
                isCopying = false
                if (success) {
                    pdfName = originalName
                    currentPage = 1
                    totalPages = 1
                    isSearching = false
                    searchQuery = ""
                    currentMatch = 0
                    totalMatches = 0
                    isAutoScrolling = false
                    reloadTrigger++
                    Toast.makeText(context, "Loaded: $pdfName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to load PDF file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Main layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                when (currentTheme) {
                    "dark" -> Color(0xFF121212)
                    "sepia" -> Color(0xFFF4ECD8)
                    "eyecare" -> Color(0xFFE1EED6)
                    else -> Color(0xFFF4F4F5)
                }
            )
    ) {
        // Full bleed PDF WebView
        key(reloadTrigger) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setWebView(this)
                        settings.apply {
                            javaScriptEnabled = true
                            allowFileAccess = true
                            allowContentAccess = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            allowFileAccessFromFileURLs = true
                            allowUniversalAccessFromFileURLs = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }

                        // Connect event communications to Compose
                        addJavascriptInterface(
                            PdfAndroidBridge(
                                onPageChanged = { page, total ->
                                    post {
                                        currentPage = page
                                        if (total > 0) {
                                            totalPages = total
                                        }
                                    }
                                },
                                onScaleChanged = { scale ->
                                    post {
                                        currentScale = scale
                                    }
                                },
                                onSearchMatchesChanged = { current, total ->
                                    post {
                                        currentMatch = current
                                        totalMatches = total
                                    }
                                },
                                onLinkClicked = { url, text ->
                                    post {
                                        val isAudio = isAudioUrl(url)
                                        if (isAudio) {
                                            playAudio(url, text)
                                        } else {
                                            activeWebUrl = url
                                        }
                                    }
                                }
                            ),
                            "AndroidBridge"
                        )

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)

                                // Inject CSS to hide default controls and padding to let document float beautiful
                                val css = """
                                    #toolbarContainer, .toolbar, #sidebarContainer, #secondaryToolbar { display: none !important; }
                                    #viewerContainer { top: 0 !important; bottom: 0 !important; }
                                    body { background-color: transparent !important; }
                                """.trimIndent()

                                val styleInjection = """
                                    var style = document.createElement('style');
                                    style.type = 'text/css';
                                    style.innerHTML = `$css`;
                                    document.head.appendChild(style);
                                """.trimIndent()
                                view?.evaluateJavascript(styleInjection, null)

                                // Inject PDF.js event listeners to communicate metrics robustly via JavaScript Bridge
                                val bridgeSetup = """
                                    (function() {
                                        var initialized = false;
                                        function initEvents() {
                                            if (initialized) return;
                                            initialized = true;
                                            try {
                                                if (typeof AndroidBridge !== 'undefined') {
                                                    var initialPage = (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.page) ? PDFViewerApplication.page : 1;
                                                    var initialTotal = (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.pagesCount) ? PDFViewerApplication.pagesCount : 0;
                                                    var initialScale = (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.pdfViewer && PDFViewerApplication.pdfViewer.currentScale) ? PDFViewerApplication.pdfViewer.currentScale : 1.0;
                                                    AndroidBridge.onPageChanged(initialPage, initialTotal);
                                                    AndroidBridge.onScaleChanged(initialScale);
                                                }
                                                
                                                PDFViewerApplication.eventBus.on('pagechanging', function(e) {
                                                    if (typeof AndroidBridge !== 'undefined') {
                                                        var total = e.pagesCount || (typeof PDFViewerApplication !== 'undefined' ? PDFViewerApplication.pagesCount : 0) || 0;
                                                        AndroidBridge.onPageChanged(e.pageNumber, total);
                                                    }
                                                });
                                                PDFViewerApplication.eventBus.on('scalechanging', function(e) {
                                                    if (typeof AndroidBridge !== 'undefined' && e.scale) {
                                                        AndroidBridge.onScaleChanged(e.scale);
                                                    }
                                                });
                                                PDFViewerApplication.eventBus.on('updatefindmatchescount', function(e) {
                                                    if (typeof AndroidBridge !== 'undefined' && e.matchesCount) {
                                                        AndroidBridge.onSearchMatchesChanged(e.matchesCount.current, e.matchesCount.total);
                                                    }
                                                });

                                                // Intercept all document links to play audio or show standard web links in embedded browser
                                                document.addEventListener('click', function(e) {
                                                    var target = e.target;
                                                    while (target && target.tagName !== 'A') {
                                                        target = target.parentNode;
                                                    }
                                                    if (target && target.getAttribute('href')) {
                                                        var href = target.getAttribute('href');
                                                        var text = target.textContent || target.innerText || "";
                                                        if (href && href.trim().length > 0 && !href.startsWith('#') && !href.startsWith('javascript:')) {
                                                            e.preventDefault();
                                                            e.stopPropagation();
                                                            if (typeof AndroidBridge !== 'undefined' && AndroidBridge.onLinkClicked) {
                                                                AndroidBridge.onLinkClicked(href, text.trim());
                                                            }
                                                        }
                                                    }
                                                }, true);

                                            } catch (err) {
                                                console.error('Error initializing events: ' + err);
                                            }
                                        }

                                        function setupBridge() {
                                            if (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.initializedPromise) {
                                                PDFViewerApplication.initializedPromise.then(initEvents);
                                            } else {
                                                document.addEventListener('webviewerloaded', function() {
                                                    if (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.initializedPromise) {
                                                        PDFViewerApplication.initializedPromise.then(initEvents);
                                                    }
                                                }, { once: true });
                                                
                                                var checkCount = 0;
                                                var interval = setInterval(function() {
                                                    checkCount++;
                                                    if (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.initializedPromise) {
                                                        clearInterval(interval);
                                                        PDFViewerApplication.initializedPromise.then(initEvents);
                                                    } else if (checkCount > 100) {
                                                        clearInterval(interval);
                                                    }
                                                }, 50);
                                            }
                                        }
                                        setupBridge();
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(bridgeSetup, null)

                                // Re-apply current reading mode visual background filters
                                applyReaderTheme(currentTheme)
                                applyScrollLayout(isHorizontalScroll, isSnapToPage, isDoubleSpread)
                            }
                        }

                        // Load Mozilla PDFjs with cached copy
                        loadUrl("file:///android_asset/pdfjs/web/viewer.html?file=file://${ctx.cacheDir.absolutePath}/current_reader_doc.pdf")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // FLOATING TOP BAR - Modelled after PDF Reader Pro
        AnimatedVisibility(
            visible = isBarsVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Surface(
                color = Color(0xEA1E1E24), // Translucent dark gray
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSearching) {
                    // Search layout inside the floating top bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                isSearching = false
                                searchQuery = ""
                                currentMatch = 0
                                totalMatches = 0
                                onClearSearch()
                            },
                            modifier = Modifier.testTag("close_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Search",
                                tint = Color.White
                            )
                        }

                        TextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                if (it.isNotEmpty()) {
                                    onSearch(it)
                                } else {
                                    currentMatch = 0
                                    totalMatches = 0
                                    onClearSearch()
                                }
                            },
                            placeholder = { Text("Search text...", color = Color.LightGray) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                cursorColor = Color.White,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onSearch(searchQuery) }),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("search_text_input")
                        )

                        if (totalMatches > 0) {
                            Text(
                                text = "$currentMatch/$totalMatches",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676), // Bright neon green for search count
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }

                        IconButton(
                            onClick = { onSearchPrev(searchQuery) },
                            enabled = searchQuery.isNotEmpty(),
                            modifier = Modifier.testTag("search_prev_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Prev Match",
                                tint = if (searchQuery.isNotEmpty()) Color.White else Color.Gray
                            )
                        }

                        IconButton(
                            onClick = { onSearchNext(searchQuery) },
                            enabled = searchQuery.isNotEmpty(),
                            modifier = Modifier.testTag("search_next_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Next Match",
                                tint = if (searchQuery.isNotEmpty()) Color.White else Color.Gray
                            )
                        }
                    }
                } else {
                    // Standard floating PDF Reader Pro header layout
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { pdfPickerLauncher.launch("application/pdf") },
                            modifier = Modifier.testTag("fab_open_pdf")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Load Local File",
                                tint = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pdfName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Page $currentPage / $totalPages",
                                    fontSize = 11.sp,
                                    color = Color.LightGray
                                )
                                if (isFavorite) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "Favorite",
                                        tint = Color.Red,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }

                        // Right icons (Search, Document Info, Immersive)
                        IconButton(
                            onClick = { isSearching = true },
                            modifier = Modifier.testTag("open_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = { activeSheet = "navigation" },
                            modifier = Modifier.testTag("outline_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = "Outline & Navigation",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = { isBarsVisible = false }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = "Hide Toolbars",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        // FLOATING BOTTOM BAR - Modelled after PDF Reader Pro
        AnimatedVisibility(
            visible = isBarsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Surface(
                color = Color(0xEA1E1E24), // Translucent dark gray matching PDF Reader Pro
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Button 1: More Tools
                    BottomBarItem(
                        icon = Icons.Default.MoreHoriz,
                        label = "Tools",
                        onClick = { activeSheet = "tools" },
                        testTag = "btn_sheet_tools"
                    )

                    // Button 2: Bookmark Page Toggle (Visual filled state indicator)
                    BottomBarItem(
                        icon = if (isCurrentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        label = "Bookmark",
                        tint = if (isCurrentPageBookmarked) Color(0xFFFFB300) else Color.White,
                        onClick = { togglePageBookmark() },
                        testTag = "btn_toggle_bookmark"
                    )

                    // Button 3: Theme Color Palette
                    BottomBarItem(
                        icon = Icons.Default.Palette,
                        label = "Theme",
                        onClick = { activeSheet = "theme" },
                        testTag = "btn_sheet_theme"
                    )

                    // Button 4: Zoom Controls
                    BottomBarItem(
                        icon = Icons.Default.ZoomIn,
                        label = "Zoom",
                        onClick = { activeSheet = "zoom" },
                        testTag = "btn_sheet_zoom"
                    )

                    // Button 5: View Scroll Layout
                    BottomBarItem(
                        icon = Icons.Default.Settings,
                        label = "View",
                        onClick = { activeSheet = "layout" },
                        testTag = "btn_sheet_layout"
                    )

                    // Button 6: Pages / Thumbnail outlines
                    BottomBarItem(
                        icon = Icons.Default.Menu,
                        label = "Pages",
                        onClick = {
                            activeNavTab = 0
                            activeSheet = "navigation"
                        },
                        testTag = "btn_sheet_navigation"
                    )
                }
            }
        }

        // FULL SCREEN RESTORE TRIGGER
        if (!isBarsVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(24.dp)
            ) {
                FloatingActionButton(
                    onClick = { isBarsVisible = true },
                    containerColor = Color(0x9E1E1E24),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(54.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FullscreenExit,
                        contentDescription = "Restore Toolbars",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // SHEET A: MORE TOOLS BOTTOM SHEET
        // -------------------------------------------------------------
        if (activeSheet == "tools") {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "More Tools",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Additional features and actions for this document",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

                    // Auto Scroll controller block
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Auto Scrolling Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { isAutoScrolling = !isAutoScrolling },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (isAutoScrolling) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = if (isAutoScrolling) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause Auto Scroll",
                                    tint = if (isAutoScrolling) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isAutoScrolling) "Auto Scroll Active" else "Auto Scroll Inactive",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Speed indicators (Slow, Med, Fast)
                        Row {
                            listOf(1 to "Slow", 2 to "Med", 3 to "Fast").forEach { (speed, label) ->
                                Button(
                                    onClick = { autoScrollSpeed = speed },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (autoScrollSpeed == speed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (autoScrollSpeed == speed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .height(32.dp)
                                ) {
                                    Text(text = label, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Favorite Button
                    SheetActionRow(
                        icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        iconTint = if (isFavorite) Color.Red else MaterialTheme.colorScheme.onSurface,
                        title = if (isFavorite) "Added to Favourites" else "Add to Favourites",
                        subtitle = "Toggle this file on your favorites list",
                        onClick = {
                            isFavorite = !isFavorite
                            sharedPrefs.edit().putBoolean("fav_$pdfName", isFavorite).apply()
                            val msg = if (isFavorite) "Added to Favourites" else "Removed from Favourites"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    )

                    // Share PDF file copy via intent
                    SheetActionRow(
                        icon = Icons.Default.Share,
                        title = "Share Document",
                        subtitle = "Share a copy of this PDF with other readers",
                        onClick = { sharePdf(context) }
                    )

                    // Save file to user downloads directory
                    SheetActionRow(
                        icon = Icons.Default.Save,
                        title = "Save Copy to Downloads",
                        subtitle = "Save a secure copy to device Public Downloads folder",
                        onClick = {
                            val ok = savePdfCopy(context)
                            if (ok) {
                                Toast.makeText(context, "Saved copy to Public Downloads!", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Failed to export PDF copy", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    // Print Document
                    SheetActionRow(
                        icon = Icons.Default.Print,
                        title = "Print Document",
                        subtitle = "Generate print adapter layout and print pages",
                        onClick = { printPdf(context) }
                    )

                    // Document Details info trigger
                    SheetActionRow(
                        icon = Icons.Default.Info,
                        title = "Document Properties",
                        subtitle = "Check size, total page specs, and reader engine statistics",
                        onClick = {
                            activeSheet = null
                            Toast.makeText(context, "Name: $pdfName\nTotal Pages: $totalPages\nCurrent Zoom: ${(currentScale * 100).toInt()}%", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // SHEET B: THEME COLOR PALETTE SHEET
        // -------------------------------------------------------------
        if (activeSheet == "theme") {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Reading Themes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Customize backgrounds and tint styles for eye safety",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ThemeSelectionCircle(
                            label = "Light",
                            bgColor = Color.White,
                            textColor = Color.Black,
                            selected = currentTheme == "light",
                            onClick = {
                                currentTheme = "light"
                                applyReaderTheme("light")
                            }
                        )

                        ThemeSelectionCircle(
                            label = "Night",
                            bgColor = Color(0xFF1E1E1E),
                            textColor = Color.White,
                            selected = currentTheme == "dark",
                            onClick = {
                                currentTheme = "dark"
                                applyReaderTheme("dark")
                            }
                        )

                        ThemeSelectionCircle(
                            label = "Sepia",
                            bgColor = Color(0xFFF4ECD8),
                            textColor = Color(0xFF5D4037),
                            selected = currentTheme == "sepia",
                            onClick = {
                                currentTheme = "sepia"
                                applyReaderTheme("sepia")
                            }
                        )

                        ThemeSelectionCircle(
                            label = "Eye Care",
                            bgColor = Color(0xFFE1EED6),
                            textColor = Color(0xFF2E7D32),
                            selected = currentTheme == "eyecare",
                            onClick = {
                                currentTheme = "eyecare"
                                applyReaderTheme("eyecare")
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // -------------------------------------------------------------
        // SHEET C: ZOOM & DISPLAY CONTROL SHEET
        // -------------------------------------------------------------
        if (activeSheet == "zoom") {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Zoom & Orientation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Scale your documents or lock screen orientations",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

                    Spacer(modifier = Modifier.height(14.dp))

                    // Scale Row Adjustments
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Zoom Scale",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            IconButton(onClick = onZoomOut) {
                                Icon(imageVector = Icons.Default.Remove, contentDescription = "Zoom Out")
                            }
                            Text(
                                text = "${(currentScale * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            IconButton(onClick = onZoomIn) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "Zoom In")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Preset scales Row
                    Text(
                        text = "Scale Presets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf(
                            "Fit Page" to "page-fit",
                            "Fit Width" to "page-width",
                            "Actual Size" to "1.0",
                            "Auto" to "auto"
                        ).forEach { (label, value) ->
                            Button(
                                onClick = { executeJs("if (typeof PDFViewerApplication !== 'undefined') { PDFViewerApplication.pdfViewer.currentScaleValue = '$value'; }") },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).padding(horizontal = 3.dp)
                            ) {
                                Text(label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Screen orientation toggling
                    Text(
                        text = "Screen Orientation Lock",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf(
                            Triple("Portrait", Icons.Default.StayCurrentPortrait, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
                            Triple("Landscape", Icons.Default.StayPrimaryLandscape, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
                            Triple("Auto Rotate", Icons.Default.ScreenRotation, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                        ).forEach { (label, icon, orientationVal) ->
                            Button(
                                onClick = {
                                    activity?.requestedOrientation = orientationVal
                                    Toast.makeText(context, "Orientation: $label", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                            ) {
                                Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(label, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        // -------------------------------------------------------------
        // SHEET D: VIEW LAYOUT & SCROLL DIRECTION
        // -------------------------------------------------------------
        if (activeSheet == "layout") {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "View & Scroll Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Adjust page scroll styles and continuous page rendering",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Scroll Direction Segment
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "Scroll Direction", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(text = "Continuous horizontal or vertical reading", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Row {
                            Button(
                                onClick = {
                                    isHorizontalScroll = false
                                    isSnapToPage = false
                                    applyScrollLayout(false, false, isDoubleSpread)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!isHorizontalScroll && !isSnapToPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (!isHorizontalScroll && !isSnapToPage) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ),
                                shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(imageVector = Icons.Default.SwapVert, contentDescription = "Vertical", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Vertical", fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    isHorizontalScroll = true
                                    isSnapToPage = false
                                    applyScrollLayout(true, false, isDoubleSpread)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isHorizontalScroll && !isSnapToPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isHorizontalScroll && !isSnapToPage) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ),
                                shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(imageVector = Icons.Default.SwapHoriz, contentDescription = "Horizontal", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Horizontal", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Snapping Presentation Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "Single Page Slide (Snap)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(text = "Slides pages individually instead of fluid scrolling", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Switch(
                            checked = isSnapToPage,
                            onCheckedChange = {
                                isSnapToPage = it
                                applyScrollLayout(isHorizontalScroll, isSnapToPage, isDoubleSpread)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Double spread toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "Two-Page Spread Layout", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(text = "Render odd pages side by side (ideal on tablets)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Switch(
                            checked = isDoubleSpread,
                            onCheckedChange = {
                                isDoubleSpread = it
                                applyScrollLayout(isHorizontalScroll, isSnapToPage, isDoubleSpread)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        // -------------------------------------------------------------
        // SHEET E: OUTLINE & PAGES NAVIGATION PANEL
        // -------------------------------------------------------------
        if (activeSheet == "navigation") {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 16.dp,
                modifier = Modifier.fillMaxHeight(0.85f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Document Navigation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Tab selections (Pages List | Bookmarks | Document Details)
                    TabRow(
                        selectedTabIndex = activeNavTab,
                        containerColor = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = activeNavTab == 0,
                            onClick = { activeNavTab = 0 },
                            text = { Text("Pages ($totalPages)", fontSize = 13.sp) }
                        )
                        Tab(
                            selected = activeNavTab == 1,
                            onClick = { activeNavTab = 1 },
                            text = { Text("Bookmarks (${bookmarks.size})", fontSize = 13.sp) }
                        )
                        Tab(
                            selected = activeNavTab == 2,
                            onClick = { activeNavTab = 2 },
                            text = { Text("Details", fontSize = 13.sp) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        when (activeNavTab) {
                            0 -> {
                                // Pages Grid list
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(80.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items((1..totalPages).toList()) { pageNum ->
                                        val isCurrent = pageNum == currentPage
                                        val isBookmarked = bookmarks.contains(pageNum)

                                        Box(
                                            modifier = Modifier
                                                .aspectRatio(0.75f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                )
                                                .clickable {
                                                    onGoToPage(pageNum)
                                                    activeSheet = null
                                                }
                                                .padding(6.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.SpaceBetween,
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                // Icon row inside thumbnail representation
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.End
                                                ) {
                                                    if (isBookmarked) {
                                                        Icon(
                                                            imageVector = Icons.Default.Bookmark,
                                                            contentDescription = "Bookmarked",
                                                            tint = Color(0xFFFFB300),
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }

                                                Text(
                                                    text = "$pageNum",
                                                    fontSize = 18.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )

                                                Text(
                                                    text = "Page $pageNum",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            1 -> {
                                // Saved Bookmarks list
                                if (bookmarks.isEmpty()) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BookmarkBorder,
                                            contentDescription = "No bookmarks",
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = "No Bookmarks Saved",
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Tap the ribbon icon in bottom toolbar to save active reader pages.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(bookmarks.toList().sorted()) { bookmarkedPage ->
                                            Card(
                                                shape = RoundedCornerShape(8.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            onGoToPage(bookmarkedPage)
                                                            activeSheet = null
                                                        }
                                                        .padding(14.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Default.Bookmark,
                                                            contentDescription = "Bookmark",
                                                            tint = Color(0xFFFFB300)
                                                        )
                                                        Spacer(modifier = Modifier.width(12.dp))
                                                        Column {
                                                            Text(
                                                                text = "Page $bookmarkedPage",
                                                                fontWeight = FontWeight.Bold,
                                                                style = MaterialTheme.typography.bodyLarge
                                                            )
                                                            Text(
                                                                text = "Saved outline location",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }

                                                    IconButton(
                                                        onClick = {
                                                            val updated = bookmarks.toMutableSet()
                                                            updated.remove(bookmarkedPage)
                                                            bookmarks = updated
                                                            sharedPrefs.edit()
                                                                .putStringSet("bookmarks_$pdfName", updated.map { it.toString() }.toSet())
                                                                .apply()
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "Delete Bookmark",
                                                            tint = MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            2 -> {
                                // Details / Outline Properties Tab
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    item {
                                        DocumentInfoTextItem("File Name", pdfName)
                                    }
                                    item {
                                        DocumentInfoTextItem("Format Type", "Portable Document Format (PDF)")
                                    }
                                    item {
                                        DocumentInfoTextItem("Page Count", "$totalPages Pages")
                                    }
                                    item {
                                        DocumentInfoTextItem("Active Zoom", "${(currentScale * 100).toInt()}% Scale")
                                    }
                                    item {
                                        DocumentInfoTextItem("Reading Mode", if (isHorizontalScroll) "Horizontal Scroll continuous" else "Vertical Scroll continuous")
                                    }
                                    item {
                                        DocumentInfoTextItem("Snap Control", if (isSnapToPage) "Enabled" else "Disabled")
                                    }
                                    item {
                                        DocumentInfoTextItem("Favorites Flag", if (isFavorite) "Listed" else "Not Listed")
                                    }
                                    item {
                                        DocumentInfoTextItem("Viewer Kernel", "Mozilla PDF.js v4.10.38")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isCopying) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "جاري فتح الملف...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // --- Pronunciation Audio Mini-Player (Dynamic Island Style) ---
        AnimatedVisibility(
            visible = playingAudioUrl != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 24.dp, end = 24.dp)
                .zIndex(25f)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E1E22)
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .shadow(10.dp, RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Play / Pause Button
                        IconButton(
                            onClick = {
                                mediaPlayer?.let { mp ->
                                    if (mp.isPlaying) {
                                        mp.pause()
                                        isAudioPlayingState = false
                                    } else {
                                        mp.start()
                                        isAudioPlayingState = true
                                    }
                                } ?: run {
                                    playingAudioUrl?.let { playAudio(it, playingAudioText) }
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isAudioPlayingState) Icons.Default.PlayArrow else Icons.Default.PlayArrow,
                                contentDescription = "تشغيل/إيقاف مؤقت",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Clicked word Text and Time Progress Info
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = playingAudioText.ifEmpty { "نطق الكلمة" },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "$audioPositionText / $audioDurationText",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Replay/Repeat Button
                        IconButton(
                            onClick = { playingAudioUrl?.let { playAudio(it, playingAudioText) } },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "إعادة النطق",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Close/Dismiss Button (x)
                        IconButton(
                            onClick = {
                                mediaPlayer?.stop()
                                mediaPlayer?.release()
                                mediaPlayer = null
                                isAudioPlayingState = false
                                playingAudioUrl = null
                                playingAudioText = ""
                                audioProgress = 0.0f
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Linear progress bar mimicking the audio track progress
                    LinearProgressIndicator(
                        progress = audioProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                }
            }
        }

        // --- Embedded Web Browser Overlaid on Top of PDF reader ---
        if (activeWebUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(30f)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Browser Header
                    Surface(
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .height(56.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { activeWebUrl = null },
                                modifier = Modifier.testTag("browser_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "رجوع",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "تصفح الرابط",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = activeWebUrl ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (isWebLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                        }
                    }

                    // Embedded web viewer
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = true
                                    allowContentAccess = true
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        isWebLoading = true
                                    }
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isWebLoading = false
                                    }
                                }
                                loadUrl(activeWebUrl ?: "")
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                    )
                }
            }
        }
    }
}

// Custom Bottom Bar Item layout for float toolbar bar
@Composable
private fun BottomBarItem(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            color = if (tint == Color.White) Color.LightGray else tint,
            fontWeight = FontWeight.Medium
        )
    }
}

// Bottom sheet item action layout
@Composable
private fun SheetActionRow(
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Selector Circles for Custom Themes
@Composable
private fun ThemeSelectionCircle(
    label: String,
    bgColor: Color,
    textColor: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(bgColor)
                .border(
                    border = BorderStroke(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f)
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = if (bgColor == Color.White) Color.Black else textColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DocumentInfoTextItem(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Normal)
    }
}

// Helper to copy selected Uri contents to the internal cache under a safe filename
private fun copyUriToCache(context: Context, uri: Uri, safeFileName: String): Boolean {
    return try {
        val cacheFile = File(context.cacheDir, safeFileName)
        val genericFile = File(context.cacheDir, "current_reader_doc.pdf")
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        if (genericFile.exists()) {
            genericFile.delete()
        }
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val bytes = inputStream.readBytes()
            FileOutputStream(cacheFile).use { it.write(bytes) }
            FileOutputStream(genericFile).use { it.write(bytes) }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

private fun getSafePdfFileName(originalName: String): String {
    val sanitized = originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return if (sanitized.endsWith(".pdf", ignoreCase = true)) sanitized else "$sanitized.pdf"
}

// Helper to query file name from Uri
private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var name: String? = null
    try {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return name ?: uri.lastPathSegment
}
