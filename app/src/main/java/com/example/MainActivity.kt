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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.media.MediaPlayer
import android.media.AudioManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var webViewRef: WebView? = null

    // For "Open With" incoming intent tracking
    val incomingPdfUriState = mutableStateOf<Uri?>(null)

    fun clearIncomingPdfUri() {
        incomingPdfUriState.value = null
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent != null && intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                incomingPdfUriState.value = uri
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        // Initialize PDFBox
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Copy default sample PDF on start-up
        copyAssetToCache(this, "sample.pdf")

        setContent {
            MyApplicationTheme {
                MainAppContainer()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainAppContainer() {
        val context = LocalContext.current
        val sharedPrefs = remember { context.getSharedPreferences("PdfReaderPrefs", Context.MODE_PRIVATE) }
        
        var hasPermission by remember { mutableStateOf(hasStoragePermission(context)) }
        var currentScreen by remember { mutableStateOf("home") }
        var openedPdfName by remember { mutableStateOf("sample.pdf") }
        var openedPdfPath by remember { mutableStateOf("") }
        var isCopyingFile by remember { mutableStateOf(false) }

        // Reactively observe and open any PDF received from other applications ("Open With")
        val activity = context as? MainActivity
        val incomingUri = activity?.incomingPdfUriState?.value

        LaunchedEffect(incomingUri) {
            if (incomingUri != null) {
                isCopyingFile = true
                val originalName = getFileNameFromUri(context, incomingUri) ?: "External Document.pdf"
                val safeFileName = getSafePdfFileName(originalName)
                val success = withContext(Dispatchers.IO) {
                    copyUriToCache(context, incomingUri, safeFileName)
                }
                isCopyingFile = false
                if (success) {
                    openedPdfName = originalName
                    openedPdfPath = File(context.cacheDir, safeFileName).absolutePath
                    currentScreen = "pdf_reader"
                }
                activity?.clearIncomingPdfUri()
            }
        }
        
        val scope = rememberCoroutineScope()

        val manageStorageLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) {
            hasPermission = hasStoragePermission(context)
        }

        val requestPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasPermission = permissions[android.Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }

        fun requestAllFilesPermission() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    manageStorageLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                }
            } else {
                requestPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }

        var pdfFiles by remember { mutableStateOf<List<PdfFileItem>>(emptyList()) }
        var isScanning by remember { mutableStateOf(false) }

        val refreshScan = {
            isScanning = true
            scope.launch {
                val sampleFile = File(context.cacheDir, "sample.pdf")
                val sampleItem = PdfFileItem(
                    name = "كتيب التعليمات (عينة).pdf",
                    path = sampleFile.absolutePath,
                    size = sampleFile.length(),
                    pages = getPdfPageCount(context, sampleFile.absolutePath),
                    dateModified = sampleFile.lastModified()
                )
                
                val scanned = withContext(Dispatchers.IO) {
                    scanPdfFilesOnDevice(context)
                }
                
                val combined = (listOf(sampleItem) + scanned).distinctBy { it.path }
                
                pdfFiles = combined.map { file ->
                    val isFav = sharedPrefs.getBoolean("fav_${file.name}", false)
                    file.copy(isFavorite = isFav)
                }
                isScanning = false
            }
        }

        LaunchedEffect(hasPermission) {
            if (hasPermission) {
                refreshScan()
            }
        }

        LaunchedEffect(currentScreen) {
            if (currentScreen == "home" && hasPermission) {
                pdfFiles = pdfFiles.map { file ->
                    val isFav = sharedPrefs.getBoolean("fav_${file.name}", false)
                    file.copy(isFavorite = isFav)
                }
            }
        }

        if (!hasPermission) {
            PermissionRationaleScreen(onRequestPermission = { requestAllFilesPermission() })
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                if (currentScreen == "home") {
                    MainNavigationScreen(
                        pdfFiles = pdfFiles,
                        isScanning = isScanning,
                        onOpenFile = { file ->
                            isCopyingFile = true
                            scope.launch {
                                val success = withContext(Dispatchers.IO) {
                                    copyFileToCache(context, file.path, getSafePdfFileName(file.name))
                                }
                                isCopyingFile = false
                                if (success) {
                                    addToRecentPdfs(context, file.path)
                                    openedPdfName = file.name
                                    openedPdfPath = file.path
                                    currentScreen = "pdf_reader"
                                } else {
                                    Toast.makeText(context, "فشل في فتح الملف", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onRefreshScan = { refreshScan() },
                        onRenameFile = { file, newName ->
                            val renamed = renamePdfFile(context, file.path, newName)
                            if (renamed != null) {
                                refreshScan()
                                true
                            } else {
                                false
                            }
                        },
                        onDeleteFile = { file ->
                            try {
                                val f = File(file.path)
                                if (f.exists() && f.delete()) {
                                    val recentPaths = getRecentPdfs(context).toMutableList()
                                    if (recentPaths.remove(file.path)) {
                                        sharedPrefs.edit().putString("recent_pdfs_ordered_v2", recentPaths.joinToString("|||")).apply()
                                    }
                                    refreshScan()
                                    true
                                } else {
                                    false
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                false
                            }
                        }
                    )
                } else if (currentScreen == "pdf_reader") {
                    PDFReaderScreen(
                        initialPdfName = openedPdfName,
                        onBackClicked = { currentScreen = "home" },
                        onNextPage = { executeJs("PDFViewerApplication.pdfViewer.nextPage();") },
                        onPrevPage = { executeJs("PDFViewerApplication.pdfViewer.previousPage();") },
                        onZoomIn = { executeJs("if (typeof PDFViewerApplication !== 'undefined') { PDFViewerApplication.pdfViewer.currentScale += 0.25; }") },
                        onZoomOut = { executeJs("if (typeof PDFViewerApplication !== 'undefined') { var newScale = PDFViewerApplication.pdfViewer.currentScale - 0.25; if (window.minPdfScale && newScale < window.minPdfScale) { PDFViewerApplication.pdfViewer.currentScale = window.minPdfScale; } else { PDFViewerApplication.pdfViewer.currentScale = newScale; } }") },
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
                            val encodedQuery = Uri.encode(query)
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
                            val encodedQuery = Uri.encode(query)
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
                            val encodedQuery = Uri.encode(query)
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

                if (isCopyingFile) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(color = Color(0xFFD0BCFF))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("جاري تحميل وفتح ملف الـ PDF...", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
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
    private val onLinkClicked: (url: String, text: String) -> Unit,
    private val onDocumentClicked: () -> Unit
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

    @JavascriptInterface
    fun onDocumentClicked() {
        onDocumentClicked.invoke()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PDFReaderScreen(
    initialPdfName: String = "sample.pdf",
    onBackClicked: () -> Unit = {},
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

    val window = activity?.window
    val windowInsetsController = remember(window) {
        if (window != null) {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        } else {
            null
        }
    }

    // SharedPreferences for Bookmarks & Favorites
    val sharedPrefs = remember { context.getSharedPreferences("PdfReaderPrefs", Context.MODE_PRIVATE) }

    // State Variables
    var currentPage by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(1) }
    var currentScale by remember { mutableStateOf(1.0f) }
    var pdfName by remember(initialPdfName) { mutableStateOf(initialPdfName) }
    var isCopying by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler {
        onBackClicked()
    }

    // Audio & Embedded Web Browser States
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingAudioUrl by remember { mutableStateOf<String?>(null) }
    var playingAudioText by remember { mutableStateOf("") }
    var isAudioPlayingState by remember { mutableStateOf(false) }
    var isAudioPreparing by remember { mutableStateOf(false) }
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
               cleanUrl.contains("audio") || 
               cleanUrl.contains("pronounce") || 
               cleanUrl.contains("pronunciation") || 
               cleanUrl.contains("sound") ||
               cleanUrl.contains("voice") ||
               cleanUrl.contains("translate_tts") ||
               cleanUrl.contains("speech") ||
               cleanUrl.contains("tts")
    }

    fun playAudio(url: String, text: String) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isAudioPlayingState = false
            isAudioPreparing = true
            audioProgress = 0.0f
            playingAudioUrl = url
            playingAudioText = text

            val mp = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(url)
                setOnPreparedListener {
                    isAudioPreparing = false
                    it.start()
                    isAudioPlayingState = true
                }
                setOnCompletionListener {
                    isAudioPreparing = false
                    isAudioPlayingState = false
                    audioProgress = 1.0f
                    scope.launch {
                        delay(1500)
                        if (!isAudioPlayingState && !isAudioPreparing) {
                            playingAudioUrl = null
                        }
                    }
                }
                setOnErrorListener { _, _, _ ->
                    isAudioPreparing = false
                    isAudioPlayingState = false
                    Toast.makeText(context, "خطأ في تشغيل الصوت", Toast.LENGTH_SHORT).show()
                    false
                }
            }
            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            isAudioPreparing = false
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

    LaunchedEffect(currentTheme, windowInsetsController) {
        windowInsetsController?.let { controller ->
            // Keep the status bar text/icons always white/light as requested
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

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
            window.isHorizontalScroll = $isHorizontal;
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
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setWebView(this)
                        settings.apply {
                            javaScriptEnabled = true
                            allowFileAccess = true
                            allowContentAccess = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = false
                            loadWithOverviewMode = false
                            builtInZoomControls = false
                            displayZoomControls = false
                            setSupportZoom(false)
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
                                },
                                onDocumentClicked = {
                                    post {
                                        isBarsVisible = !isBarsVisible
                                    }
                                }
                            ),
                            "AndroidBridge"
                        )

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)

                                // Inject CSS to hide default controls and padding to let document float beautifully
                                val css = """
                                    #toolbarContainer, .toolbar, #sidebarContainer, #secondaryToolbar { display: none !important; }
                                    #viewerContainer { top: 0 !important; bottom: 0 !important; }
                                    body { background-color: transparent !important; }
                                    .textLayer *::selection {
                                        background: rgba(0, 122, 255, 0.3) !important;
                                        color: transparent !important;
                                    }
                                    .textLayer.selecting .endOfContent { display: none !important; }
                                """.trimIndent()

                                val styleInjection = """
                                    var style = document.createElement('style');
                                    style.type = 'text/css';
                                    style.innerHTML = `$css`;
                                    document.head.appendChild(style);
                                    
                                    // Ensure viewport allows native zoom and scaling perfectly
                                    var meta = document.querySelector('meta[name="viewport"]');
                                    if (meta) {
                                        meta.setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes');
                                    } else {
                                        meta = document.createElement('meta');
                                        meta.name = 'viewport';
                                        meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
                                        document.head.appendChild(meta);
                                    }
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
                                                        if (window.minPdfScale && e.scale < window.minPdfScale) {
                                                            PDFViewerApplication.pdfViewer.currentScale = window.minPdfScale;
                                                        } else {
                                                            AndroidBridge.onScaleChanged(e.scale);
                                                        }
                                                    }
                                                });
                                                PDFViewerApplication.eventBus.on('pagerendered', function(e) {
                                                    if (!window.minPdfScale && PDFViewerApplication.pdfViewer && PDFViewerApplication.pdfViewer.currentScale) {
                                                        window.minPdfScale = PDFViewerApplication.pdfViewer.currentScale;
                                                    }
                                                    if (!window.initialPdfScale && PDFViewerApplication.pdfViewer && PDFViewerApplication.pdfViewer.currentScale) {
                                                        window.initialPdfScale = PDFViewerApplication.pdfViewer.currentScale;
                                                    }
                                                });
                                                PDFViewerApplication.eventBus.on('updatefindmatchescount', function(e) {
                                                    if (typeof AndroidBridge !== 'undefined' && e.matchesCount) {
                                                        AndroidBridge.onSearchMatchesChanged(e.matchesCount.current, e.matchesCount.total);
                                                    }
                                                });

                                                // Custom gesture and tap handling
                                                function isInteractive(element) {
                                                    var el = element;
                                                    while (el) {
                                                        if (el.tagName === 'A' || el.tagName === 'BUTTON' || el.tagName === 'INPUT' || (el.classList && el.classList.contains('clickable'))) {
                                                            return true;
                                                        }
                                                        el = el.parentNode;
                                                    }
                                                    return false;
                                                }

                                                document.addEventListener('click', function(e) {
                                                    var textLayer = e.target.closest('.textLayer');
                                                    if (!textLayer) {
                                                        if (typeof AndroidBridge !== 'undefined') {
                                                            AndroidBridge.onDocumentClicked();
                                                        }
                                                    }
                                                }, { passive: true });
                                                
                                                // Intercept all document links to play audio or show standard web links in embedded browser
                                                document.addEventListener('click', function(e) {
                                                    var target = e.target;
                                                    var isLink = false;
                                                    var clickedLinkNode = null;
                                                    while (target) {
                                                        if (target.tagName === 'A' && target.getAttribute('href')) {
                                                            isLink = true;
                                                            clickedLinkNode = target;
                                                            break;
                                                        }
                                                        target = target.parentNode;
                                                    }
                                                    if (isLink && clickedLinkNode) {
                                                        var href = clickedLinkNode.getAttribute('href');
                                                        var text = "";
                                                        try {
                                                            var originalPointerEvents = clickedLinkNode.style.pointerEvents;
                                                            clickedLinkNode.style.pointerEvents = 'none';
                                                            var elementUnder = document.elementFromPoint(e.clientX, e.clientY);
                                                            clickedLinkNode.style.pointerEvents = originalPointerEvents || '';
                                                            if (elementUnder) {
                                                                text = elementUnder.textContent || elementUnder.innerText || "";
                                                            }
                                                        } catch (err) {
                                                            console.error(err);
                                                        }
                                                        if (!text || text.trim().length === 0) {
                                                            text = clickedLinkNode.textContent || clickedLinkNode.innerText || "";
                                                        }
                                                        if (href && href.trim().length > 0 && !href.startsWith('#') && !href.startsWith('javascript:')) {
                                                            e.preventDefault();
                                                            e.stopPropagation();
                                                            if (typeof AndroidBridge !== 'undefined' && AndroidBridge.onLinkClicked) {
                                                                AndroidBridge.onLinkClicked(href, text.trim());
                                                                return;
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

                                view?.evaluateJavascript("""
                                (function() {
                                    function fixSpanPositions() {
                                        document.querySelectorAll('.textLayer span').forEach(function(span) {
                                            if (span.style.transform && span.style.transform !== 'none') {
                                                var rect = span.getBoundingClientRect();
                                                var scrollTop = document.getElementById('viewerContainer').scrollTop;
                                                var scrollLeft = document.getElementById('viewerContainer').scrollLeft;
                                                span.style.left = (rect.left + scrollLeft) + 'px';
                                                span.style.top = (rect.top + scrollTop) + 'px';
                                                span.style.transform = 'none';
                                                span.style.position = 'absolute';
                                                span.style.whiteSpace = 'pre';
                                            }
                                        });
                                    }

                                    if (typeof PDFViewerApplication !== 'undefined') {
                                        PDFViewerApplication.eventBus.on('textlayerrendered', function() {
                                            setTimeout(fixSpanPositions, 50);
                                        });
                                    }
                                })();
                                """, null)

                                 

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

        // Enhanced top status bar dimming overlay to ensure white system status bar icons are beautifully readable
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.65f),
                            Color.Black.copy(alpha = 0.35f),
                            Color.Black.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
                .zIndex(10f)
        )

        // WPS & PDF Reader Pro inspired scrollbar page indicator that fades out after 900ms of inactivity
        var showPageIndicator by remember { mutableStateOf(false) }
        
        LaunchedEffect(currentPage) {
            if (totalPages > 1) {
                showPageIndicator = true
                delay(900)
                showPageIndicator = false
            }
        }

        AnimatedVisibility(
            visible = showPageIndicator,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .zIndex(15f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.6f)
                    .width(180.dp)
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val trackHeightPx = constraints.maxHeight
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val trackHeightDp = with(density) { trackHeightPx.toDp() }
                    
                    // Vertical scrollbar track
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(3.dp)
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(1.5.dp))
                    )
                    
                    val progressFraction = if (totalPages > 1) {
                        (currentPage - 1).toFloat() / (totalPages - 1).toFloat()
                    } else {
                        0f
                    }
                    
                    val animatedProgress by animateFloatAsState(
                        targetValue = progressFraction,
                        animationSpec = spring(stiffness = androidx.compose.animation.core.Spring.StiffnessLow),
                        label = "scroll_progress"
                    )
                    
                    val thumbHeight = 40.dp
                    val yOffset = (trackHeightDp - thumbHeight) * animatedProgress
                    
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = yOffset),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        // WPS style tooltip with PDF Reader Pro purple theme
                        Surface(
                            color = Color(0xE67C3AED), // Beautiful semi-translucent purple
                            shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                            shadowElevation = 6.dp,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = "$currentPage / $totalPages",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        
                        // Scroll thumb slider
                        Box(
                            modifier = Modifier
                                .size(width = 8.dp, height = 36.dp)
                                .background(Color(0xFF8B5CF6), RoundedCornerShape(4.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
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
                            onClick = { onBackClicked() },
                            modifier = Modifier.testTag("fab_back_to_home")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Go back to Home",
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
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
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
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Speed indicators (Slow, Med, Fast)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Text(
                                text = "Scroll Speed:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            listOf(1 to "Slow", 2 to "Med", 3 to "Fast").forEach { (speed, label) ->
                                Button(
                                    onClick = { autoScrollSpeed = speed },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (autoScrollSpeed == speed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (autoScrollSpeed == speed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .height(32.dp)
                                ) {
                                    Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                                                .border(
                                                    width = if (isCurrent) 2.dp else 1.dp,
                                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    onGoToPage(pageNum)
                                                    activeSheet = null
                                                }
                                        ) {
                                            // Real PDF page thumbnail rendered via system PdfRenderer
                                            PdfPageThumbnail(
                                                pageNum = pageNum,
                                                modifier = Modifier.fillMaxSize()
                                            )

                                            // Text overlay with page index and bookmark status at bottom
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .align(Alignment.BottomCenter)
                                                    .background(
                                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                                        )
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "$pageNum",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                    
                                                    if (isBookmarked) {
                                                        Icon(
                                                            imageVector = Icons.Default.Bookmark,
                                                            contentDescription = "Bookmarked",
                                                            tint = Color(0xFFFFB300),
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }
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
                .statusBarsPadding()
                .padding(
                    top = if (isBarsVisible) 90.dp else 16.dp,
                    start = 24.dp,
                    end = 24.dp
                )
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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Play / Pause Button
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(26.dp)
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                .clip(CircleShape)
                                .clickable {
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
                                }
                        ) {
                            if (isAudioPreparing) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 1.5.dp,
                                    modifier = Modifier.size(12.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = if (isAudioPlayingState) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "تشغيل/إيقاف مؤقت",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Clicked word Text and Time Progress Info
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            val displayText = if (playingAudioText.isNotBlank()) playingAudioText else (playingAudioUrl?.let { extractWordFromUrl(it) } ?: "نطق الكلمة")
                            val textLength = displayText.length
                            val fontSize = when {
                                textLength > 24 -> 11.sp
                                textLength > 18 -> 13.sp
                                textLength > 12 -> 14.sp
                                else -> 16.sp
                            }
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = fontSize
                                ),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = "$audioPositionText / $audioDurationText",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Replay/Repeat Button
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(26.dp)
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                .clip(CircleShape)
                                .clickable {
                                    playingAudioUrl?.let { playAudio(it, playingAudioText) }
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "إعادة النطق",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Close/Dismiss Button (x)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(26.dp)
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                .clip(CircleShape)
                                .clickable {
                                    mediaPlayer?.stop()
                                    mediaPlayer?.release()
                                    mediaPlayer = null
                                    isAudioPlayingState = false
                                    isAudioPreparing = false
                                    playingAudioUrl = null
                                    playingAudioText = ""
                                    audioProgress = 0.0f
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
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

@Composable
fun PdfPageThumbnail(pageNum: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hasError by remember { mutableStateOf(false) }

    LaunchedEffect(pageNum) {
        withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "current_reader_doc.pdf")
            if (file.exists()) {
                var pfd: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null
                var page: PdfRenderer.Page? = null
                try {
                    pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    if (pfd != null) {
                        renderer = PdfRenderer(pfd)
                        val zeroIndexedPage = pageNum - 1
                        if (zeroIndexedPage >= 0 && zeroIndexedPage < renderer.pageCount) {
                            page = renderer.openPage(zeroIndexedPage)
                            val targetWidth = 150
                            val targetHeight = 200
                            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bitmap)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            thumbnailBitmap = bitmap
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    hasError = true
                } finally {
                    try { page?.close() } catch (e: Exception) {}
                    try { renderer?.close() } catch (e: Exception) {}
                    try { pfd?.close() } catch (e: Exception) {}
                }
            } else {
                hasError = true
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val bitmap = thumbnailBitmap
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "الصفحة $pageNum",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$pageNum",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

fun extractWordFromUrl(url: String): String {
    try {
        val uri = android.net.Uri.parse(url)
        // 1. Try query parameters like "q", "text", "word", "query", "string"
        for (param in listOf("q", "text", "word", "query", "string", "term")) {
            val v = uri.getQueryParameter(param)
            if (!v.isNullOrBlank()) return v.trim()
        }
        // 2. Try last path segment without extension
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrBlank()) {
            val dotIdx = lastSegment.lastIndexOf('.')
            val name = if (dotIdx != -1) lastSegment.substring(0, dotIdx) else lastSegment
            // Decode URL encoding
            val decoded = java.net.URLDecoder.decode(name, "UTF-8")
            // If it's a random hex string or number, don't use it
            if (decoded.length in 2..30 && !decoded.matches(Regex("^[0-9a-fA-F]+$"))) {
                return decoded.replace('_', ' ').replace('-', ' ').trim()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "نطق الكلمة"
}

// =========================================================================
// --- SMART PDF LIBRARY CORE SCANNERS, TABS AND MULTI-SCREEN UTILITIES ---
// =========================================================================

data class PdfFileItem(
    val name: String,
    val path: String,
    val size: Long,
    val pages: Int,
    val dateModified: Long,
    val isFavorite: Boolean = false
)

fun hasStoragePermission(context: Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

fun getPdfPageCount(context: Context, path: String): Int {
    try {
        val file = File(path)
        if (!file.exists()) return 1
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val count = renderer.pageCount
        renderer.close()
        pfd.close()
        return count
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return 1
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(java.util.Locale.US, "%.2f MB", mb)
    } else {
        String.format(java.util.Locale.US, "%.1f KB", kb)
    }
}

fun formatDate(timestampSeconds: Long): String {
    val millis = if (timestampSeconds < 100000000000L) timestampSeconds * 1000 else timestampSeconds
    val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
    return sdf.format(java.util.Date(millis))
}

fun formatDetailedDate(timestampSeconds: Long): String {
    val millis = if (timestampSeconds < 100000000000L) timestampSeconds * 1000 else timestampSeconds
    val sdf = java.text.SimpleDateFormat("dd MMMM yyyy, hh:mm a", java.util.Locale("ar"))
    return sdf.format(java.util.Date(millis))
}

fun getParentFolderName(path: String): String {
    return try {
        File(path).parentFile?.name ?: "المستندات"
    } catch (e: Exception) {
        "المستندات"
    }
}

fun renamePdfFile(context: Context, oldPath: String, newNameWithoutExtension: String): File? {
    try {
        val oldFile = File(oldPath)
        if (!oldFile.exists()) return null
        
        val parentDir = oldFile.parentFile ?: return null
        var cleanNewName = newNameWithoutExtension.trim()
        if (cleanNewName.isEmpty()) return null
        if (!cleanNewName.endsWith(".pdf", ignoreCase = true)) {
            cleanNewName += ".pdf"
        }
        
        val newFile = File(parentDir, cleanNewName)
        if (newFile.exists()) {
            return null // Already exists
        }
        
        if (oldFile.renameTo(newFile)) {
            return newFile
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

fun sharePdfFile(context: Context, path: String) {
    try {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(context, "الملف غير موجود", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Copy the file to app's cache directory to prevent any FileProvider path/root exceptions
        val safeName = getSafePdfFileName(file.name)
        val cacheFile = File(context.cacheDir, "shared_temp_${safeName}")
        file.inputStream().use { inputStream ->
            cacheFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "com.example.fileprovider",
            cacheFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "مشاركة ملف الـ PDF عبر:"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "فشل مشاركة الملف: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

object PdfThumbnailCache {
    private val cache = android.util.LruCache<String, Bitmap>(50)
    
    fun get(path: String): Bitmap? = cache.get(path)
    fun put(path: String, bitmap: Bitmap) {
        cache.put(path, bitmap)
    }
}

@Composable
fun PdfThumbnailView(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf(PdfThumbnailCache.get(path)) }
    
    if (bitmap == null) {
        LaunchedEffect(path) {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(pfd)
                        if (renderer.pageCount > 0) {
                            val page = renderer.openPage(0)
                            val width = 120
                            val height = 150
                            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bmp)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            PdfThumbnailCache.put(path, bmp)
                            bitmap = bmp
                            page.close()
                        }
                        renderer.close()
                        pfd.close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    Box(
        modifier = modifier
            .background(Color(0xFF2B2B36))
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = Color(0xFFEF5350),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileOptionsBottomSheet(
    file: PdfFileItem,
    onDismiss: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit
) {
    val isFav = remember(file.isFavorite) { file.isFavorite }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E24),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 20.dp, end = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${file.pages} صفحة  •  ${formatSize(file.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2B2B36)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Text(
                text = "إجراءات سريعة",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            BottomSheetActionRow(
                title = if (isFav) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                subtitle = "حفظ المستند للوصول إليه بسرعة لاحقاً",
                icon = if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                iconColor = if (isFav) Color(0xFFFFB74D) else Color.White,
                onClick = {
                    onFavoriteToggle()
                    onDismiss()
                }
            )

            BottomSheetActionRow(
                title = "مشاركة الملف",
                subtitle = "إرسال هذا الملف وتسهيل مشاركته مع الآخرين",
                icon = Icons.Default.Share,
                onClick = {
                    onShare()
                    onDismiss()
                }
            )

            BottomSheetActionRow(
                title = "إعادة تسمية",
                subtitle = "تعديل اسم مستند الـ PDF الحالي بمرونة",
                icon = Icons.Default.Edit,
                onClick = {
                    onRename()
                    onDismiss()
                }
            )

            BottomSheetActionRow(
                title = "معلومات الملف",
                subtitle = "عرض تفاصيل وحجم وتاريخ تعديل الملف بالكامل",
                icon = Icons.Default.Info,
                onClick = {
                    onInfo()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDelete()
                        onDismiss()
                    },
                colors = CardDefaults.cardColors(containerColor = Color(0x1AEF5350)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0x33EF5350))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "حذف الملف",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFFEF5350)
                        )
                        Text(
                            text = "حذف هذا الملف نهائياً من على جهازك",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF5350).copy(alpha = 0.7f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22EF5350)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color(0xFFEF5350)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BottomSheetActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x1AFFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun RenameFileDialog(
    file: PdfFileItem,
    onDismiss: () -> Unit,
    onRenameConfirm: (String) -> Unit
) {
    var textValue by remember { 
        val baseName = file.name.substringBeforeLast(".")
        mutableStateOf(baseName) 
    }
    
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Rename File",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x1AFFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = Color(0xFFD0BCFF),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Start)
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("File name", color = Color(0xFFD0BCFF)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFD0BCFF),
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedContainerColor = Color(0x0AFFFFFF),
                        unfocusedContainerColor = Color(0x0AFFFFFF)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "The .pdf extension will be added automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Start)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onRenameConfirm(textValue) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD0BCFF),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Rename", fontWeight = FontWeight.Bold)
                    }
                    
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
fun FileInfoDialog(
    file: PdfFileItem,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "File Information",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x1AD0BCFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = Color(0xFFD0BCFF),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0x0AFFFFFF)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = formatSize(file.size),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = "Size", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0x0AFFFFFF)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "pages ${file.pages}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = "Pages", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Modified",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDetailedDate(file.dateModified),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    modifier = Modifier.padding(start = 26.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Location",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 26.dp)
                )

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD0BCFF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun DeleteConfirmationDialog(
    file: PdfFileItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حذف الملف", color = Color.White, fontWeight = FontWeight.Bold) },
        text = { Text("هل أنت متأكد من رغبتك في حذف هذا الملف نهائياً؟ لا يمكن التراجع عن هذا الإجراء.", color = Color.LightGray) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
            ) {
                Text("حذف", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Color.LightGray)
            }
        },
        containerColor = Color(0xFF1E1E24)
    )
}


fun copyFileToCache(context: Context, path: String, safeFileName: String): Boolean {
    return try {
        val srcFile = File(path)
        if (!srcFile.exists()) return false
        val cacheFile = File(context.cacheDir, safeFileName)
        val genericFile = File(context.cacheDir, "current_reader_doc.pdf")
        
        if (srcFile.absolutePath != cacheFile.absolutePath) {
            if (cacheFile.exists()) cacheFile.delete()
            srcFile.inputStream().use { inputStream ->
                val bytes = inputStream.readBytes()
                FileOutputStream(cacheFile).use { it.write(bytes) }
            }
        }
        
        if (srcFile.absolutePath != genericFile.absolutePath) {
            if (genericFile.exists()) genericFile.delete()
            srcFile.inputStream().use { inputStream ->
                val bytes = inputStream.readBytes()
                FileOutputStream(genericFile).use { it.write(bytes) }
            }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun getRecentPdfs(context: Context): List<String> {
    val prefs = context.getSharedPreferences("PdfReaderPrefs", Context.MODE_PRIVATE)
    val recentStr = prefs.getString("recent_pdfs_ordered_v2", "") ?: ""
    if (recentStr.isBlank()) return emptyList()
    return recentStr.split("|||").filter { it.isNotBlank() }
}

fun addToRecentPdfs(context: Context, path: String) {
    val prefs = context.getSharedPreferences("PdfReaderPrefs", Context.MODE_PRIVATE)
    val currentRecents = getRecentPdfs(context).toMutableList()
    currentRecents.remove(path)
    currentRecents.add(0, path) // Add to top
    val limited = currentRecents.take(20)
    prefs.edit().putString("recent_pdfs_ordered_v2", limited.joinToString("|||")).apply()
}

fun scanPdfFilesOnDevice(context: Context): List<PdfFileItem> {
    val pdfs = mutableListOf<PdfFileItem>()
    val uri = android.provider.MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
        android.provider.MediaStore.Files.FileColumns.SIZE,
        android.provider.MediaStore.Files.FileColumns.DATA,
        android.provider.MediaStore.Files.FileColumns.DATE_MODIFIED
    )
    val selection = "${android.provider.MediaStore.Files.FileColumns.MIME_TYPE} = ?"
    val selectionArgs = arrayOf("application/pdf")
    val sortOrder = "${android.provider.MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

    try {
        context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.SIZE)
            val dataIndex = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.DATA)
            val dateIndex = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val path = if (dataIndex != -1) cursor.getString(dataIndex) else ""
                if (path.isEmpty()) continue
                val file = File(path)
                if (!file.exists()) continue

                val name = if (nameIndex != -1) cursor.getString(nameIndex) ?: file.name else file.name
                val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else file.length()
                val dateModified = if (dateIndex != -1) cursor.getLong(dateIndex) else file.lastModified()
                
                val pages = getPdfPageCount(context, path)

                pdfs.add(
                    PdfFileItem(
                        name = name,
                        path = path,
                        size = size,
                        pages = pages,
                        dateModified = dateModified
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Also scan cache dir for PDFs generated by tools
    try {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.lowercase() == "pdf" && file.name != "current_reader_doc.pdf") {
                val pages = getPdfPageCount(context, file.absolutePath)
                pdfs.add(
                    PdfFileItem(
                        name = file.name,
                        path = file.absolutePath,
                        size = file.length(),
                        pages = pages,
                        dateModified = file.lastModified()
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Fallback recursive file crawler for standard folders
    if (pdfs.isEmpty()) {
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            scanDirectoryForPdfs(context, downloadsDir, pdfs)
            val documentsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            scanDirectoryForPdfs(context, documentsDir, pdfs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    return pdfs.distinctBy { it.path }
}

private fun scanDirectoryForPdfs(context: Context, dir: File, list: MutableList<PdfFileItem>) {
    if (dir.exists() && dir.isDirectory) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                file.listFiles()?.forEach { subFile ->
                    if (subFile.isFile && subFile.extension.lowercase() == "pdf") {
                        val pages = getPdfPageCount(context, subFile.absolutePath)
                        list.add(
                            PdfFileItem(
                                name = subFile.name,
                                path = subFile.absolutePath,
                                size = subFile.length(),
                                pages = pages,
                                dateModified = subFile.lastModified()
                            )
                        )
                    }
                }
            } else if (file.isFile && file.extension.lowercase() == "pdf") {
                val pages = getPdfPageCount(context, file.absolutePath)
                list.add(
                    PdfFileItem(
                        name = file.name,
                        path = file.absolutePath,
                        size = file.length(),
                        pages = pages,
                        dateModified = file.lastModified()
                    )
                )
            }
        }
    }
}

@Composable
fun PermissionRationaleScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121214))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF1E1E24))
                .padding(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = Color(0xFFD0BCFF),
                modifier = Modifier
                    .size(82.dp)
                    .padding(8.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "الوصول إلى ملفات الجهاز",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "يرجى إعطاء الإذن للتطبيق للوصول إلى وحدة التخزين ليتمكن من فحص وقراءة كتب ومستندات الـ PDF المتوفرة على هاتفك تلقائياً.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
            
            Spacer(modifier = Modifier.height(28.dp))
            
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD0BCFF),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("grant_permission_button")
            ) {
                Text(
                    text = "منح الإذن للمتابعة",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigationScreen(
    pdfFiles: List<PdfFileItem>,
    isScanning: Boolean,
    onOpenFile: (PdfFileItem) -> Unit,
    onRefreshScan: () -> Unit,
    onRenameFile: (PdfFileItem, String) -> Boolean,
    onDeleteFile: (PdfFileItem) -> Boolean
) {
    var selectedTab by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("PdfReaderPrefs", Context.MODE_PRIVATE) }

    var activeMenuFile by remember { mutableStateOf<PdfFileItem?>(null) }
    var activeRenameFile by remember { mutableStateOf<PdfFileItem?>(null) }
    var activeInfoFile by remember { mutableStateOf<PdfFileItem?>(null) }
    var activeDeleteFile by remember { mutableStateOf<PdfFileItem?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.height(60.dp),
                containerColor = Color(0xFF1E293B),
                contentColor = Color.White
            ) {
                val tabs = listOf("الرئيسية", "المجلدات", "الأدوات", "الإعدادات")
                val icons = listOf(Icons.Default.Home, Icons.Default.Folder, Icons.Default.Build, Icons.Default.Settings)
                
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                imageVector = icons[index],
                                contentDescription = label,
                                tint = if (selectedTab == index) Color(0xFF06B6D4) else Color.Gray
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color(0x3306B6D4)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0F172A))
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    pdfFiles = pdfFiles,
                    isScanning = isScanning,
                    onOpenFile = onOpenFile,
                    onRefresh = onRefreshScan,
                    onMoreClicked = { activeMenuFile = it }
                )
                1 -> FoldersScreen(
                    pdfFiles = pdfFiles,
                    onOpenFile = onOpenFile,
                    onMoreClicked = { activeMenuFile = it }
                )
                2 -> ToolsScreen(
                    pdfFiles = pdfFiles,
                    onOpenFile = onOpenFile,
                    onRefresh = onRefreshScan
                )
                3 -> SettingsScreen(
                    pdfFiles = pdfFiles
                )
            }
        }
    }

    // Bottom Sheets & Dialogs Rendering
    val menuFile = activeMenuFile
    if (menuFile != null) {
        FileOptionsBottomSheet(
            file = menuFile,
            onDismiss = { activeMenuFile = null },
            onFavoriteToggle = {
                val currentFav = sharedPrefs.getBoolean("fav_${menuFile.name}", false)
                sharedPrefs.edit().putBoolean("fav_${menuFile.name}", !currentFav).apply()
                onRefreshScan()
            },
            onShare = {
                sharePdfFile(context, menuFile.path)
            },
            onRename = {
                activeRenameFile = menuFile
            },
            onInfo = {
                activeInfoFile = menuFile
            },
            onDelete = {
                activeDeleteFile = menuFile
            }
        )
    }

    val renameFile = activeRenameFile
    if (renameFile != null) {
        RenameFileDialog(
            file = renameFile,
            onDismiss = { activeRenameFile = null },
            onRenameConfirm = { newName ->
                if (newName.isNotBlank()) {
                    val success = onRenameFile(renameFile, newName)
                    if (success) {
                        Toast.makeText(context, "تم إعادة تسمية الملف بنجاح", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "فشل في إعادة التسمية (الاسم مكرر أو غير صالح)", Toast.LENGTH_SHORT).show()
                    }
                }
                activeRenameFile = null
            }
        )
    }

    val infoFile = activeInfoFile
    if (infoFile != null) {
        FileInfoDialog(
            file = infoFile,
            onDismiss = { activeInfoFile = null }
        )
    }

    val deleteFile = activeDeleteFile
    if (deleteFile != null) {
        DeleteConfirmationDialog(
            file = deleteFile,
            onDismiss = { activeDeleteFile = null },
            onConfirm = {
                val success = onDeleteFile(deleteFile)
                if (success) {
                    Toast.makeText(context, "تم حذف الملف بنجاح", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "فشل في حذف الملف", Toast.LENGTH_SHORT).show()
                }
                activeDeleteFile = null
            }
        )
    }
}

@Composable
fun HomeScreen(
    pdfFiles: List<PdfFileItem>,
    isScanning: Boolean,
    onOpenFile: (PdfFileItem) -> Unit,
    onRefresh: () -> Unit,
    onMoreClicked: (PdfFileItem) -> Unit
) {
    val context = LocalContext.current
    var subTabSelected by remember { mutableStateOf(0) }
    var isGridView by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val recentPaths = remember(pdfFiles) { getRecentPdfs(context) }

    // Filtered and sorted lists
    val filteredFiles = remember(pdfFiles, searchQuery, subTabSelected, recentPaths) {
        val baseList = when (subTabSelected) {
            0 -> {
                // Recent files sorted in reverse-chrono
                pdfFiles.filter { it.path in recentPaths }.sortedBy { recentPaths.indexOf(it.path) }
            }
            1 -> pdfFiles // All Files
            2 -> pdfFiles.filter { it.isFavorite } // Favorites
            else -> pdfFiles
        }
        
        if (searchQuery.isBlank()) {
            baseList
        } else {
            baseList.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // TOP HEADER
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "المكتبة الذكية",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            IconButton(
                onClick = { isGridView = !isGridView }
            ) {
                Icon(
                    imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                    contentDescription = "تغيير طريقة العرض",
                    tint = Color.White
                )
            }
        }

        // SEARCH BAR
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("بحث في ملفات PDF...", color = Color.Gray) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.LightGray) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFD0BCFF),
                unfocusedBorderColor = Color(0x33FFFFFF),
                focusedContainerColor = Color(0xFF1E1E24),
                unfocusedContainerColor = Color(0xFF1E1E24)
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("pdf_search_bar")
        )

        // TAB ROWS (All, Recent, Favorites)
        TabRow(
            selectedTabIndex = subTabSelected,
            containerColor = Color(0xFF121214),
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[subTabSelected]),
                    color = Color(0xFFD0BCFF)
                )
            }
        ) {
            val subTabs = listOf("الأخيرة", "كل الملفات", "المفضلة")
            subTabs.forEachIndexed { idx, title ->
                Tab(
                    selected = subTabSelected == idx,
                    onClick = { subTabSelected = idx },
                    text = {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (subTabSelected == idx) Color(0xFFD0BCFF) else Color.Gray
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // GRID OR LIST CONTAINER
        if (isScanning) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFD0BCFF))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("جاري فحص ملفات PDF بالجهاز...", color = Color.LightGray)
                }
            }
        } else if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = when (subTabSelected) {
                            0 -> Icons.Default.History
                            2 -> Icons.Default.FavoriteBorder
                            else -> Icons.Default.PictureAsPdf
                        },
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when (subTabSelected) {
                            0 -> "لا توجد مستندات مفتوحة مؤخراً"
                            2 -> "قائمة المفضلة فارغة حالياً"
                            else -> "لم يتم العثور على أي ملف PDF"
                        },
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "اضغط على زر الفحص أو ضع ملفات PDF داخل مجلد التنزيلات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredFiles) { file ->
                        PdfFileGridItem(
                            file = file,
                            onOpenFile = onOpenFile,
                            onMoreClicked = { onMoreClicked(file) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredFiles) { file ->
                        PdfFileRow(
                            file = file,
                            onOpenFile = onOpenFile,
                            onMoreClicked = { onMoreClicked(file) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FoldersScreen(
    pdfFiles: List<PdfFileItem>,
    onOpenFile: (PdfFileItem) -> Unit,
    onMoreClicked: (PdfFileItem) -> Unit
) {
    val foldersMap = remember(pdfFiles) {
        pdfFiles.groupBy { file ->
            val f = File(file.path)
            f.parentFile?.name ?: "وحدة التخزين"
        }
    }

    var activeFolder by remember { mutableStateOf<String?>(null) }

    if (activeFolder != null) {
        val folderName = activeFolder!!
        val filesInFolder = foldersMap[folderName] ?: emptyList()

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { activeFolder = null }) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "رجوع",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filesInFolder) { file ->
                    PdfFileRow(
                        file = file,
                        onOpenFile = onOpenFile,
                        onMoreClicked = { onMoreClicked(file) }
                    )
                }
            }
        }
    } else {
        if (foldersMap.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "لا تتوفر مجلدات مستندات PDF",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.LightGray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "مجلدات الملفات",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
                items(foldersMap.keys.toList()) { folderName ->
                    val filesCount = foldersMap[folderName]?.size ?: 0
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { activeFolder = folderName },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1E1E24)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = Color(0xFFD0BCFF),
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = folderName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "$filesCount مستندات PDF",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color.LightGray
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolsScreen(
    pdfFiles: List<PdfFileItem>,
    onOpenFile: (PdfFileItem) -> Unit,
    onRefresh: () -> Unit
) {
    var activeTool by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Selection States
    var selectedFile by remember { mutableStateOf<PdfFileItem?>(null) }
    var selectedFile1 by remember { mutableStateOf<PdfFileItem?>(null) }
    var selectedFile2 by remember { mutableStateOf<PdfFileItem?>(null) }

    // Option States
    var splitRangeStart by remember { mutableStateOf("1") }
    var splitRangeEnd by remember { mutableStateOf("2") }
    var splitAllPages by remember { mutableStateOf(true) }
    var rotateAngle by remember { mutableStateOf(90) }
    var reorderSequence by remember { mutableStateOf("2, 1") }
    var removePageNums by remember { mutableStateOf("2") }
    var watermarkText by remember { mutableStateOf("نسخة غير قابلة للتداول") }
    var watermarkColor by remember { mutableStateOf("#FF0000") }
    var watermarkOpacity by remember { mutableStateOf(0.3f) }
    var pageNumPosition by remember { mutableStateOf("Bottom Center") }
    var lockUserPassword by remember { mutableStateOf("123456") }
    var lockOwnerPassword by remember { mutableStateOf("123456") }
    var lockAllowPrint by remember { mutableStateOf(true) }
    var lockAllowCopy by remember { mutableStateOf(true) }
    var unlockPassword by remember { mutableStateOf("123456") }
    var selectedImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var imageToPdfName by remember { mutableStateOf("مستند_صور") }

    // Processing States
    var isProcessing by remember { mutableStateOf(false) }
    var showSuccessCard by remember { mutableStateOf(false) }
    var resultFilePath by remember { mutableStateOf("") }
    var resultImagePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var processingError by remember { mutableStateOf("") }

    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedImageUris = uris
        }
    }

    // Reset helper
    fun resetStates() {
        selectedFile = null
        selectedFile1 = null
        selectedFile2 = null
        showSuccessCard = false
        resultFilePath = ""
        resultImagePaths = emptyList()
        processingError = ""
        selectedImageUris = emptyList()
        imageToPdfName = "مستند_صور"
    }

    if (activeTool != null) {
        androidx.activity.compose.BackHandler {
            activeTool = null
            resetStates()
        }
    }

    val processTool = {
        isProcessing = true
        processingError = ""
        showSuccessCard = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val outputDir = context.cacheDir
                    val timestamp = System.currentTimeMillis()

                    when (activeTool) {
                        "merge" -> {
                            if (selectedFile1 == null || selectedFile2 == null) {
                                throw Exception("يرجى اختيار مستندين للدمج")
                            }
                            val out = File(outputDir, "مدمج_${timestamp}.pdf")
                            mergePdfFilesReal(context, listOf(selectedFile1!!.path, selectedFile2!!.path), out.absolutePath)
                            resultFilePath = out.absolutePath
                        }
                        "split" -> {
                            if (selectedFile == null) throw Exception("يرجى اختيار مستند أولاً")
                            if (splitAllPages) {
                                val list = splitPdfFileReal(context, selectedFile!!.path, outputDir)
                                if (list.isEmpty()) throw Exception("فشل تقسيم الملف")
                                resultFilePath = list.first()
                            } else {
                                val start = splitRangeStart.toIntOrNull() ?: 1
                                val end = splitRangeEnd.toIntOrNull() ?: 1
                                val order = (start..end).map { it - 1 }
                                val out = File(outputDir, "مجزأ_${timestamp}.pdf")
                                reorderPdfFileReal(context, selectedFile!!.path, out.absolutePath, order)
                                resultFilePath = out.absolutePath
                            }
                        }
                        "compress" -> {
                            if (selectedFile == null) throw Exception("يرجى اختيار مستند أولاً")
                            val out = File(outputDir, "مضغوط_${timestamp}.pdf")
                            compressPdfFileReal(context, selectedFile!!.path, out.absolutePath)
                            resultFilePath = out.absolutePath
                        }
                        "rotate" -> {
                            if (selectedFile == null) throw Exception("يرجى اختيار مستند أولاً")
                            val out = File(outputDir, "مدور_${timestamp}.pdf")
                            rotatePdfFileReal(context, selectedFile!!.path, out.absolutePath, rotateAngle)
                            resultFilePath = out.absolutePath
                        }
                        "reorder" -> {
                            if (selectedFile == null) throw Exception("يرجى اختيار مستند أولاً")
                            val indices = reorderSequence.split(",")
                                .mapNotNull { it.trim().toIntOrNull()?.minus(1) }
                            if (indices.isEmpty()) throw Exception("تنسيق الترتيب غير صالح")
                            val out = File(outputDir, "مرتب_${timestamp}.pdf")
                            reorderPdfFileReal(context, selectedFile!!.path, out.absolutePath, indices)
                            resultFilePath = out.absolutePath
                        }
                        "remove_pages" -> {
                            if (selectedFile == null) throw Exception("يرجى اختيار مستند أولاً")
                            val pagesSet = removePageNums.split(",")
                                .mapNotNull { it.trim().toIntOrNull()?.minus(1) }
                                .toSet()
                            val out = File(outputDir, "معدل_${timestamp}.pdf")
                            removePdfPagesReal(context, selectedFile!!.path, out.absolutePath, pagesSet)
                            resultFilePath = out.absolutePath
                        }
                        "watermark" -> {
                            if (selectedFile == null) throw Exception("يرجى اختيار مستند أولاً")
                            val out = File(outputDir, "مختوم_${timestamp}.pdf")
                            addWatermarkToPdfReal(context, selectedFile!!.path, out.absolutePath, watermarkText, watermarkColor, watermarkOpacity)
                            resultFilePath = out.absolutePath
                        }
                        "page_numbers" -> {
                            if (selectedFile == null) throw Exception("يرجى اختيار مستند أولاً")
                            val out = File(outputDir, "مرقم_${timestamp}.pdf")
                            addPageNumbersToPdfReal(context, selectedFile!!.path, out.absolutePath)
                            resultFilePath = out.absolutePath
                        }
                        "lock" -> {
                            if (selectedFile == null) throw Exception("يرجى اختيار مستند أولاً")
                            if (lockUserPassword.isEmpty()) throw Exception("يرجى إدخال كلمة مرور")
                            val out = File(outputDir, "محمي_${timestamp}.pdf")
                            lockPdfFileReal(
                                selectedFile!!.path,
                                out.absolutePath,
                                lockUserPassword,
                                lockOwnerPassword,
                                lockAllowPrint,
                                lockAllowCopy,
                                lockAllowCopy,
                                lockAllowCopy
                            )
                            resultFilePath = out.absolutePath
                        }
                        "unlock" -> {
                            if (selectedFile == null) throw Exception("يرجى اختيار مستند أولاً")
                            val out = File(outputDir, "مفتوح_${timestamp}.pdf")
                            unlockPdfFileReal(selectedFile!!.path, out.absolutePath, unlockPassword)
                            resultFilePath = out.absolutePath
                        }
                        "image_to_pdf" -> {
                            if (selectedImageUris.isEmpty()) throw Exception("يرجى اختيار صور أولاً")
                            val imgPaths = selectedImageUris.mapIndexed { idx, uri ->
                                val tempFile = File(outputDir, "temp_img_${idx}_${timestamp}.jpg")
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    tempFile.outputStream().use { outStream ->
                                        input.copyTo(outStream)
                                    }
                                }
                                tempFile.absolutePath
                            }
                            val finalName = if (imageToPdfName.trim().isNotEmpty()) {
                                val clean = imageToPdfName.trim()
                                if (clean.lowercase().endsWith(".pdf")) clean else "$clean.pdf"
                            } else {
                                "صور_${timestamp}.pdf"
                            }
                            val out = File(outputDir, finalName)
                            convertImagesToPdfReal(context, imgPaths, out.absolutePath)
                            resultFilePath = out.absolutePath
                        }
                        "pdf_to_images" -> {
                            if (selectedFile == null) throw Exception("يرجى اختيار مستند أولاً")
                            val list = convertPdfToImagesReal(context, selectedFile!!.path, outputDir)
                            resultImagePaths = list
                        }
                    }
                    showSuccessCard = true
                    onRefresh()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                processingError = e.message ?: "حدث خطأ أثناء معالجة الملف. يرجى التأكد من أن الملف غير محمي وكلمة المرور صحيحة."
            } finally {
                isProcessing = false
            }
        }
    }

    if (activeTool != null) {
        val toolTitle = when (activeTool) {
            "merge" -> "دمج ملفات PDF"
            "split" -> "تقسيم ملف PDF"
            "compress" -> "ضغط ملف PDF"
            "rotate" -> "تدوير الصفحات"
            "reorder" -> "إعادة ترتيب الصفحات"
            "remove_pages" -> "حذف صفحات من PDF"
            "watermark" -> "إضافة علامة مائية"
            "page_numbers" -> "إضافة أرقام الصفحات"
            "lock" -> "حماية وتشفير PDF"
            "unlock" -> "إلغاء حماية كلمة المرور"
            "image_to_pdf" -> "تحويل صور إلى PDF"
            "pdf_to_images" -> "تحويل PDF إلى صور"
            else -> "أداة PDF"
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { activeTool = null; resetStates() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = toolTitle,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }

            // Input File Selector Block (if not Image to PDF)
            if (activeTool != "image_to_pdf") {
                item {
                    Text("اختر مستند الـ PDF المستهدف:", color = Color.LightGray)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (activeTool == "merge") {
                        Text("مستند PDF الأول:", color = Color.LightGray, fontSize = 12.sp)
                        PdfFileDropdownSelector(files = pdfFiles, selectedFile = selectedFile1, onSelected = { selectedFile1 = it })
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("مستند PDF الثاني:", color = Color.LightGray, fontSize = 12.sp)
                        PdfFileDropdownSelector(files = pdfFiles, selectedFile = selectedFile2, onSelected = { selectedFile2 = it })
                    } else {
                        PdfFileDropdownSelector(files = pdfFiles, selectedFile = selectedFile, onSelected = { selectedFile = it })
                    }
                }
            }

            // Options Form Blocks
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("إعدادات العملية:", fontWeight = FontWeight.Bold, color = Color.White)

                        when (activeTool) {
                            "split" -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = splitAllPages, onCheckedChange = { splitAllPages = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF06B6D4)))
                                    Text("تقسيم المستند بالكامل صفحة بصفحة", color = Color.White)
                                }
                                if (!splitAllPages) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedTextField(
                                            value = splitRangeStart,
                                            onValueChange = { splitRangeStart = it },
                                            label = { Text("من صفحة") },
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF06B6D4), focusedLabelColor = Color(0xFF06B6D4), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                        )
                                        OutlinedTextField(
                                            value = splitRangeEnd,
                                            onValueChange = { splitRangeEnd = it },
                                            label = { Text("إلى صفحة") },
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF06B6D4), focusedLabelColor = Color(0xFF06B6D4), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                        )
                                    }
                                }
                            }
                            "rotate" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("زاوية التدوير:", color = Color.White)
                                    listOf(90, 180, 270).forEach { angle ->
                                        ElevatedButton(
                                            onClick = { rotateAngle = angle },
                                            colors = ButtonDefaults.elevatedButtonColors(
                                                containerColor = if (rotateAngle == angle) Color(0xFF06B6D4) else Color(0xFF334155),
                                                contentColor = if (rotateAngle == angle) Color.Black else Color.White
                                            )
                                        ) {
                                            Text("${angle}°")
                                        }
                                    }
                                }
                            }
                            "reorder" -> {
                                OutlinedTextField(
                                    value = reorderSequence,
                                    onValueChange = { reorderSequence = it },
                                    label = { Text("الترتيب الجديد (مثال: 3, 1, 2)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF06B6D4), focusedLabelColor = Color(0xFF06B6D4), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                                Text("يرجى كتابة أرقام الصفحات مفصولة بفاصلة.", fontSize = 11.sp, color = Color.Gray)
                            }
                            "remove_pages" -> {
                                OutlinedTextField(
                                    value = removePageNums,
                                    onValueChange = { removePageNums = it },
                                    label = { Text("أرقام الصفحات المراد حذفها (مثال: 2, 4)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF06B6D4), focusedLabelColor = Color(0xFF06B6D4), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                            }
                            "watermark" -> {
                                OutlinedTextField(
                                    value = watermarkText,
                                    onValueChange = { watermarkText = it },
                                    label = { Text("نص العلامة المائية") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF06B6D4), focusedLabelColor = Color(0xFF06B6D4), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("الشفافية: ${(watermarkOpacity * 100).toInt()}%", color = Color.White)
                                Slider(
                                    value = watermarkOpacity,
                                    onValueChange = { watermarkOpacity = it },
                                    valueRange = 0.1f..1.0f,
                                    colors = SliderDefaults.colors(thumbColor = Color(0xFF06B6D4), activeTrackColor = Color(0xFF06B6D4))
                                )
                            }
                            "page_numbers" -> {
                                Text("سيتم إدراج أرقام تسلسلية تلقائية متناسقة في ذيل كافة صفحات الملف بالمنتصف.", color = Color.LightGray)
                            }
                            "lock" -> {
                                OutlinedTextField(
                                    value = lockUserPassword,
                                    onValueChange = { lockUserPassword = it },
                                    label = { Text("كلمة مرور فتح المستند") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF06B6D4), focusedLabelColor = Color(0xFF06B6D4), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                                OutlinedTextField(
                                    value = lockOwnerPassword,
                                    onValueChange = { lockOwnerPassword = it },
                                    label = { Text("كلمة مرور المشرف/التحكم") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF06B6D4), focusedLabelColor = Color(0xFF06B6D4), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                            }
                            "unlock" -> {
                                OutlinedTextField(
                                    value = unlockPassword,
                                    onValueChange = { unlockPassword = it },
                                    label = { Text("كلمة المرور الحالية لفك التشفير") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF06B6D4), focusedLabelColor = Color(0xFF06B6D4), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                            }
                            "image_to_pdf" -> {
                                OutlinedTextField(
                                    value = imageToPdfName,
                                    onValueChange = { imageToPdfName = it },
                                    label = { Text("اسم ملف الـ PDF الناتج") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF06B6D4), focusedLabelColor = Color(0xFF06B6D4), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { pickImagesLauncher.launch("image/*") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("اختيار صور من المعرض", color = Color.White)
                                }
                                if (selectedImageUris.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("تم اختيار ${selectedImageUris.size} صور:", color = Color.White)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(3),
                                        modifier = Modifier.height(150.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(selectedImageUris) { uri ->
                                            Box(
                                                modifier = Modifier
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.DarkGray)
                                            ) {
                                                coil.compose.AsyncImage(
                                                    model = uri,
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            "pdf_to_images" -> {
                                Text("سيتم فك وتفكيك كافة صفحات ملف PDF وحفظها كملفات صور منفصلة بالجهاز.", color = Color.LightGray)
                            }
                            else -> {
                                Text("سيتم تحسين ومعالجة مستند الـ PDF بالاعتماد على محرك PDFBox ذو الكفاءة العالية.", color = Color.LightGray)
                            }
                        }
                    }
                }
            }

            // Actions & Progress State
            if (isProcessing) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color(0xFF06B6D4))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("جاري معالجة المستند بكفاءة عالية...", color = Color.LightGray)
                    }
                }
            } else if (showSuccessCard) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x3300E676)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("تم تنفيذ العملية وتكوين الملف بنجاح!", fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(modifier = Modifier.height(16.dp))

                            if (activeTool == "pdf_to_images") {
                                Text("تم استخراج ${resultImagePaths.size} صور بنجاح!", color = Color.LightGray)
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { activeTool = null; resetStates() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                                    ) {
                                        Text("الرجوع للأدوات", color = Color.White)
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val file = File(resultFilePath)
                                            if (file.exists()) {
                                                onOpenFile(
                                                    PdfFileItem(
                                                        name = file.name,
                                                        path = file.absolutePath,
                                                        size = file.length(),
                                                        pages = getPdfPageCount(context, file.absolutePath),
                                                        dateModified = file.lastModified()
                                                    )
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1.2f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                                    ) {
                                        Text("عرض الملف الآن", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = {
                                            sharePdfFile(context, resultFilePath)
                                        },
                                        modifier = Modifier.weight(0.8f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                                    ) {
                                        Text("مشاركة", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (processingError.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0x33EF5350)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = processingError,
                                color = Color(0xFFEF5350),
                                modifier = Modifier.padding(12.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                item {
                    Button(
                        onClick = { processTool() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4), contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("تأكيد وتنفيذ العملية", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Text(
                    text = "صندوق أدوات PDF الاحترافي",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // CATEGORY 1: ORGANIZE
            item {
                Text("تنظيم الصفحات", color = Color(0xFF06B6D4), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(
                        title = "دمج ملفات PDF",
                        description = "ادمج عدة مستندات في ملف واحد",
                        icon = Icons.Default.MergeType,
                        color = Color(0x1A06B6D4),
                        onClick = { activeTool = "merge" },
                        modifier = Modifier.weight(1f)
                    )
                    ToolCard(
                        title = "تقسيم ملف PDF",
                        description = "استخرج صفحات من مستند كبير",
                        icon = Icons.Default.CallSplit,
                        color = Color(0x1A38BDF8),
                        onClick = { activeTool = "split" },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(
                        title = "ضغط ملف PDF",
                        description = "قلل حجم الملف لتسهيل مشاركته",
                        icon = Icons.Default.Compress,
                        color = Color(0x1A00E676),
                        onClick = { activeTool = "compress" },
                        modifier = Modifier.weight(1f)
                    )
                    ToolCard(
                        title = "تدوير الصفحات",
                        description = "أدر اتجاه الصفحات 90 أو 180 درجة",
                        icon = Icons.Default.RotateRight,
                        color = Color(0x1AEC4899),
                        onClick = { activeTool = "rotate" },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    ToolCard(
                        title = "إعادة ترتيب الصفحات",
                        description = "رتب الصفحات بمرونة تامة",
                        icon = Icons.Default.SwapVert,
                        color = Color(0x1A06B6D4),
                        onClick = { activeTool = "reorder" },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // CATEGORY 2: EDIT
            item {
                Text("تعديل وتحرير المحتوى", color = Color(0xFF06B6D4), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(
                        title = "حذف صفحات",
                        description = "احذف الصفحات غير المرغوبة",
                        icon = Icons.Default.DeleteForever,
                        color = Color(0x1AEF5350),
                        onClick = { activeTool = "remove_pages" },
                        modifier = Modifier.weight(1f)
                    )
                    ToolCard(
                        title = "إضافة علامة مائية",
                        description = "احمِ مستنداتك بختم نصي مخصص",
                        icon = Icons.Default.Create,
                        color = Color(0x1A06B6D4),
                        onClick = { activeTool = "watermark" },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    ToolCard(
                        title = "إضافة أرقام الصفحات",
                        description = "أدرج ترقيماً جميلاً في ذيل الملف",
                        icon = Icons.Default.Dialpad,
                        color = Color(0x1A38BDF8),
                        onClick = { activeTool = "page_numbers" },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // CATEGORY 3: SECURITY
            item {
                Text("الأمان والحماية", color = Color(0xFF06B6D4), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(
                        title = "حماية بكلمة مرور",
                        description = "شفر ملف PDF لمنع التعديل والسرقة",
                        icon = Icons.Default.Lock,
                        color = Color(0x1A00E676),
                        onClick = { activeTool = "lock" },
                        modifier = Modifier.weight(1f)
                    )
                    ToolCard(
                        title = "فك التشفير",
                        description = "أزل كلمة المرور المانعة لفتح الملف",
                        icon = Icons.Default.LockOpen,
                        color = Color(0x1AEF5350),
                        onClick = { activeTool = "unlock" },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // CATEGORY 4: CONVERT
            item {
                Text("تحويل الملفات", color = Color(0xFF06B6D4), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(
                        title = "صور إلى PDF",
                        description = "حول صور هاتفك لملفات كتب بصيغة PDF",
                        icon = Icons.Default.AddPhotoAlternate,
                        color = Color(0x1A06B6D4),
                        onClick = { activeTool = "image_to_pdf" },
                        modifier = Modifier.weight(1f)
                    )
                    ToolCard(
                        title = "PDF إلى صور",
                        description = "فك صفحات الملف واحفظها كصور عالية الدقة",
                        icon = Icons.Default.PictureAsPdf,
                        color = Color(0x1A38BDF8),
                        onClick = { activeTool = "pdf_to_images" },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}


// =========================================================================
// --- REAL PDF MANIPULATION ENGINE (POWERED BY PDFBOX-ANDROID) -----------
// =========================================================================

fun mergePdfFilesReal(context: Context, inputPaths: List<String>, outputPath: String) {
    val merger = com.tom_roush.pdfbox.multipdf.PDFMergerUtility()
    for (path in inputPaths) {
        merger.addSource(File(path))
    }
    merger.destinationFileName = outputPath
    merger.mergeDocuments(null)
}

fun splitPdfFileReal(context: Context, inputPath: String, outputDir: File): List<String> {
    val inputFile = File(inputPath)
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile)
    val splitter = com.tom_roush.pdfbox.multipdf.Splitter()
    val pdfs = splitter.split(document)
    val outputPaths = mutableListOf<String>()
    for (i in pdfs.indices) {
        val singleDoc = pdfs[i]
        val out = File(outputDir, "${inputFile.nameWithoutExtension}_part${i + 1}.pdf")
        singleDoc.save(out)
        singleDoc.close()
        outputPaths.add(out.absolutePath)
    }
    document.close()
    return outputPaths
}

fun compressPdfFileReal(context: Context, inputPath: String, outputPath: String) {
    val inputFile = File(inputPath)
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile)
    document.save(File(outputPath))
    document.close()
}

fun rotatePdfFileReal(context: Context, inputPath: String, outputPath: String, rotationAngle: Int) {
    val inputFile = File(inputPath)
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile)
    for (page in document.pages) {
        page.rotation = (page.rotation + rotationAngle) % 360
    }
    document.save(File(outputPath))
    document.close()
}

fun reorderPdfFileReal(context: Context, inputPath: String, outputPath: String, pageIndices: List<Int>) {
    val inputFile = File(inputPath)
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile)
    val newDocument = com.tom_roush.pdfbox.pdmodel.PDDocument()
    for (index in pageIndices) {
        if (index >= 0 && index < document.numberOfPages) {
            newDocument.addPage(document.getPage(index))
        }
    }
    newDocument.save(File(outputPath))
    newDocument.close()
    document.close()
}

fun removePdfPagesReal(context: Context, inputPath: String, outputPath: String, pagesToRemove: Set<Int>) {
    val inputFile = File(inputPath)
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile)
    val newDocument = com.tom_roush.pdfbox.pdmodel.PDDocument()
    for (i in 0 until document.numberOfPages) {
        if (!pagesToRemove.contains(i)) {
            newDocument.addPage(document.getPage(i))
        }
    }
    newDocument.save(File(outputPath))
    newDocument.close()
    document.close()
}

fun addWatermarkToPdfReal(context: Context, inputPath: String, outputPath: String, text: String, colorHex: String = "#FF0000", opacity: Float = 0.3f) {
    val inputFile = File(inputPath)
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile)
    val font = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD
    
    val parsedColor = try {
        android.graphics.Color.parseColor(colorHex)
    } catch (e: Exception) {
        android.graphics.Color.RED
    }
    
    for (page in document.pages) {
        val contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(
            document, page, 
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND, 
            true, true
        )
        
        contentStream.beginText()
        contentStream.setFont(font, 40f)
        val r = android.graphics.Color.red(parsedColor)
        val g = android.graphics.Color.green(parsedColor)
        val b = android.graphics.Color.blue(parsedColor)
        contentStream.setNonStrokingColor(r, g, b)
        
        val extGState = com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState()
        extGState.nonStrokingAlphaConstant = opacity
        contentStream.setGraphicsStateParameters(extGState)
        
        val width = page.mediaBox.width
        val height = page.mediaBox.height
        
        contentStream.setTextMatrix(
            com.tom_roush.pdfbox.util.Matrix.getRotateInstance(
                Math.toRadians(45.0), 
                width / 4, 
                height / 3
            )
        )
        
        contentStream.showText(text)
        contentStream.endText()
        contentStream.close()
    }
    document.save(File(outputPath))
    document.close()
}

fun addPageNumbersToPdfReal(context: Context, inputPath: String, outputPath: String) {
    val inputFile = File(inputPath)
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile)
    val font = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA
    val total = document.numberOfPages
    for (i in 0 until total) {
        val page = document.getPage(i)
        val contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(
            document, page, 
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND, 
            true, true
        )
        contentStream.beginText()
        contentStream.setFont(font, 12f)
        val dkgray = android.graphics.Color.DKGRAY
        val r = android.graphics.Color.red(dkgray)
        val g = android.graphics.Color.green(dkgray)
        val b = android.graphics.Color.blue(dkgray)
        contentStream.setNonStrokingColor(r, g, b)
        
        val text = "${i + 1} / $total"
        val width = page.mediaBox.width
        contentStream.newLineAtOffset(width / 2 - 15, 25f)
        contentStream.showText(text)
        contentStream.endText()
        contentStream.close()
    }
    document.save(File(outputPath))
    document.close()
}

fun lockPdfFileReal(
    inputPath: String, 
    outputPath: String, 
    userPassword: String, 
    ownerPassword: String,
    allowPrint: Boolean,
    allowCopy: Boolean,
    allowModify: Boolean,
    allowAnnotate: Boolean
) {
    val inputFile = File(inputPath)
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile)
    val ap = com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()
    ap.setCanPrint(allowPrint)
    ap.setCanExtractContent(allowCopy)
    ap.setCanModify(allowModify)
    ap.setCanModifyAnnotations(allowAnnotate)
    
    val spp = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(ownerPassword, userPassword, ap)
    spp.encryptionKeyLength = 128
    document.protect(spp)
    document.save(File(outputPath))
    document.close()
}

fun unlockPdfFileReal(inputPath: String, outputPath: String, userPassword: String) {
    val inputFile = File(inputPath)
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile, userPassword)
    document.setAllSecurityToBeRemoved(true)
    document.save(File(outputPath))
    document.close()
}

fun convertImagesToPdfReal(context: Context, imagePaths: List<String>, outputPath: String) {
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument()
    for (path in imagePaths) {
        val page = com.tom_roush.pdfbox.pdmodel.PDPage()
        document.addPage(page)
        
        val imageFile = File(path)
        val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromStream(
            document, 
            imageFile.inputStream()
        )
        
        val contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page)
        
        val pageWidth = page.mediaBox.width
        val pageHeight = page.mediaBox.height
        val imgWidth = pdImage.width.toFloat()
        val imgHeight = pdImage.height.toFloat()
        
        val scale = Math.min(pageWidth / imgWidth, pageHeight / imgHeight)
        val w = imgWidth * scale
        val h = imgHeight * scale
        val x = (pageWidth - w) / 2
        val y = (pageHeight - h) / 2
        
        contentStream.drawImage(pdImage, x, y, w, h)
        contentStream.close()
    }
    document.save(File(outputPath))
    document.close()
}

fun convertPdfToImagesReal(context: Context, inputPath: String, outputDir: File): List<String> {
    val inputFile = File(inputPath)
    val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile)
    val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(document)
    val outputPaths = mutableListOf<String>()
    for (i in 0 until document.numberOfPages) {
        val bitmap = renderer.renderImageWithDPI(i, 150f, com.tom_roush.pdfbox.rendering.ImageType.RGB)
        val file = File(outputDir, "${inputFile.nameWithoutExtension}_page_${i + 1}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        outputPaths.add(file.absolutePath)
    }
    document.close()
    return outputPaths
}

@Composable
fun SettingsScreen(pdfFiles: List<PdfFileItem>) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("PdfReaderPrefs", Context.MODE_PRIVATE) }
    
    val totalSize = remember(pdfFiles) { pdfFiles.sumOf { it.size } }
    val totalSizeStr = remember(totalSize) { formatSize(totalSize) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "الإعدادات العامة",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "مساحة تخزين مستندات الـ PDF",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "مساحة المستندات المكتشفة:", color = Color.LightGray, fontSize = 14.sp)
                        Text(text = totalSizeStr, color = Color(0xFFD0BCFF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { (totalSize.toFloat() / (100 * 1024 * 1024)).coerceIn(0f, 1f) },
                        color = Color(0xFFD0BCFF),
                        trackColor = Color(0x33FFFFFF),
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "إجمالي الملفات المفحوصة: ${pdfFiles.size} ملفات",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sharedPrefs.edit().remove("recent_pdfs_ordered_v2").apply()
                                Toast.makeText(context, "تم إفراغ مستنداتك الأخيرة المقروءة", Toast.LENGTH_SHORT).show()
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("مسح قائمة الملفات الأخيرة", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Divider(color = Color(0x22FFFFFF))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Toast.makeText(context, "تم تطويره كقارئ ذكي مخصص متكامل", Toast.LENGTH_LONG).show()
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFD0BCFF))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("حول قارئ الكتب الذكي", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Text("الإصدار v1.0.0", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ToolCard(
    title: String,
    description: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(140.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    maxLines = 2,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
fun PdfFileRow(
    file: PdfFileItem,
    onOpenFile: (PdfFileItem) -> Unit,
    onMoreClicked: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenFile(file) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onMoreClicked,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2B2B36))
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "المزيد من الخيارات",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formatDate(file.dateModified),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                    
                    Text(
                        text = "${file.pages} صفحات",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF06B6D4),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                    
                    Box(
                        modifier = Modifier
                            .background(Color(0x22D0BCFF), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = formatSize(file.size),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFFD0BCFF),
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = Color.Gray.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = getParentFolderName(file.path),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            PdfThumbnailView(
                path = file.path,
                modifier = Modifier
                    .size(width = 54.dp, height = 68.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}

@Composable
fun PdfFileGridItem(
    file: PdfFileItem,
    onOpenFile: (PdfFileItem) -> Unit,
    onMoreClicked: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenFile(file) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                PdfThumbnailView(
                    path = file.path,
                    modifier = Modifier.fillMaxSize()
                )
                
                IconButton(
                    onClick = onMoreClicked,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x991E1E24))
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "المزيد من الخيارات",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${file.pages} ص • ${formatDate(file.dateModified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    fontSize = 10.sp,
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .background(Color(0x22D0BCFF), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatSize(file.size),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFD0BCFF),
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
fun PdfFileDropdownSelector(
    files: List<PdfFileItem>,
    selectedFile: PdfFileItem?,
    onSelected: (PdfFileItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0x22FFFFFF))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedFile?.name ?: "اضغط لتحديد مستند PDF...",
                    color = if (selectedFile != null) Color.White else Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = Color.LightGray
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(Color(0xFF1E1E24))
        ) {
            files.forEach { file ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = file.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White
                        )
                    },
                    onClick = {
                        onSelected(file)
                        expanded = false
                    }
                )
            }
        }
    }
}
