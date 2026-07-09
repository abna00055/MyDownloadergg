package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : ComponentActivity() {

    private var webViewRef: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Prepare initial sample PDF from assets to cache on start-up
        copyAssetToCache(this, "sample.pdf")

        setContent {
            MyApplicationTheme {
                PDFReaderScreen(
                    onNextPage = { executeJs("PDFViewerApplication.pdfViewer.nextPage();") },
                    onPrevPage = { executeJs("PDFViewerApplication.pdfViewer.previousPage();") },
                    onZoomIn = { executeJs("PDFViewerApplication.pdfViewer.currentScale += 0.25;") },
                    onZoomOut = { executeJs("PDFViewerApplication.pdfViewer.currentScale -= 0.25;") },
                    onGoToPage = { pageNum -> executeJs("PDFViewerApplication.pdfViewer.currentPageNumber = $pageNum;") },
                    onToggleScrollMode = { isHorizontal ->
                        val mode = if (isHorizontal) 1 else 0
                        executeJs("PDFViewerApplication.pdfViewer.scrollMode = $mode;")
                    },
                    onToggleSnapMode = { isSnap ->
                        val mode = if (isSnap) 3 else 0
                        executeJs("PDFViewerApplication.pdfViewer.scrollMode = $mode;")
                    },
                    onSearch = { query ->
                        executeJs("PDFViewerApplication.findController.executeCommand('find', { query: '$query', phraseSearch: true, caseSensitive: false, entireWord: false, highlightAll: true, findPrevious: false });")
                    },
                    onSearchNext = { query ->
                        executeJs("PDFViewerApplication.findController.executeCommand('findagain', { query: '$query', phraseSearch: true, caseSensitive: false, entireWord: false, highlightAll: true, findPrevious: false });")
                    },
                    onSearchPrev = { query ->
                        executeJs("PDFViewerApplication.findController.executeCommand('findagain', { query: '$query', phraseSearch: true, caseSensitive: false, entireWord: false, highlightAll: true, findPrevious: true });")
                    },
                    onClearSearch = {
                        executeJs("PDFViewerApplication.findController.executeCommand('find', { query: '', highlightAll: false });")
                    },
                    setWebView = { webViewRef = it }
                )
            }
        }
    }

    private fun executeJs(script: String) {
        webViewRef?.post {
            webViewRef?.evaluateJavascript(script, null)
        }
    }

    private fun copyAssetToCache(context: Context, assetName: String) {
        try {
            val cacheFile = File(context.cacheDir, "temp.pdf")
            context.assets.open(assetName).use { inputStream ->
                FileOutputStream(cacheFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// Javascript bridge for PDF.js event communication to Compose
class PdfAndroidBridge(
    private val onPageChanged: (page: Int, total: Int) -> Unit,
    private val onScaleChanged: (scale: Float) -> Unit,
    private val onSearchMatchesChanged: (current: Int, total: Int) -> Unit
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

    // State Variables
    var currentPage by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(1) }
    var currentScale by remember { mutableStateOf(1.0f) }
    var pdfName by remember { mutableStateOf("sample.pdf") }

    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatch by remember { mutableStateOf(0) }
    var totalMatches by remember { mutableStateOf(0) }

    var isHorizontalScroll by remember { mutableStateOf(false) }
    var isSnapToPage by remember { mutableStateOf(false) }

    var showGoToPageDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    var reloadTrigger by remember { mutableStateOf(0) }

    // Launcher for selecting external PDFs
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            val success = copyUriToCache(context, selectedUri)
            if (success) {
                pdfName = getFileNameFromUri(context, selectedUri) ?: "External Document.pdf"
                currentPage = 1
                totalPages = 1
                isSearching = false
                searchQuery = ""
                currentMatch = 0
                totalMatches = 0
                reloadTrigger++
                Toast.makeText(context, "Loaded: $pdfName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to load PDF file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (isSearching) {
                        // Search Mode active TopBar
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = {
                                    isSearching = false
                                    searchQuery = ""
                                    onClearSearch()
                                },
                                modifier = Modifier.testTag("close_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Search"
                                )
                            }

                            TextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    if (it.isNotEmpty()) {
                                        onSearch(it)
                                    } else {
                                        onClearSearch()
                                    }
                                },
                                placeholder = { Text("Search in document...") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
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
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }

                            IconButton(
                                onClick = { onSearchPrev(searchQuery) },
                                enabled = searchQuery.isNotEmpty(),
                                modifier = Modifier.testTag("search_prev_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Previous Match"
                                )
                            }

                            IconButton(
                                onClick = { onSearchNext(searchQuery) },
                                enabled = searchQuery.isNotEmpty(),
                                modifier = Modifier.testTag("search_next_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Next Match"
                                )
                            }
                        }
                    } else {
                        // Standard Mode TopBar
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = "PDF Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pdfName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "PDF.js Reader Engine",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = { isSearching = true },
                                modifier = Modifier.testTag("open_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search PDF"
                                )
                            }

                            IconButton(
                                onClick = { showInfoDialog = true },
                                modifier = Modifier.testTag("info_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Document Info"
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            // Native Custom Bottom Bar
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier = Modifier
                    .navigationBarsPadding()
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Page Navigator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            IconButton(
                                onClick = onPrevPage,
                                enabled = currentPage > 1,
                                modifier = Modifier.testTag("prev_page_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "Previous Page"
                                )
                            }

                            Text(
                                text = "$currentPage / $totalPages",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { showGoToPageDialog = true }
                                    .padding(horizontal = 8.dp)
                                    .testTag("page_indicator_text")
                            )

                            IconButton(
                                onClick = onNextPage,
                                enabled = currentPage < totalPages,
                                modifier = Modifier.testTag("next_page_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Next Page"
                                )
                            }
                        }

                        // Divider
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        )

                        // Zoom Controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            IconButton(
                                onClick = onZoomOut,
                                modifier = Modifier.testTag("zoom_out_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "Zoom Out"
                                )
                            }

                            Text(
                                text = "${(currentScale * 100).toInt()}%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )

                            IconButton(
                                onClick = onZoomIn,
                                modifier = Modifier.testTag("zoom_in_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Zoom In"
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Reading View Options (Horizontal/Vertical Scroll, Snap-to-Page)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                isHorizontalScroll = !isHorizontalScroll
                                onToggleScrollMode(isHorizontalScroll)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isHorizontalScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (isHorizontalScroll) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("scroll_mode_button")
                        ) {
                            Icon(
                                imageVector = if (isHorizontalScroll) Icons.Default.SwapHoriz else Icons.Default.SwapVert,
                                contentDescription = "Scroll Mode",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isHorizontalScroll) "Horizontal" else "Vertical",
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = {
                                isSnapToPage = !isSnapToPage
                                onToggleSnapMode(isSnapToPage)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSnapToPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (isSnapToPage) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("snap_mode_button")
                        ) {
                            Icon(
                                imageVector = if (isSnapToPage) Icons.Default.GridOn else Icons.Default.MenuBook,
                                contentDescription = "Snap Mode",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isSnapToPage) "Page Snap" else "Continuous",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            // FAB on the right side of the screen to choose and open any PDF file
            FloatingActionButton(
                onClick = { pdfPickerLauncher.launch("application/pdf") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .testTag("fab_open_pdf")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Open PDF Document",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // WebView container loading PDF.js viewer.html
            key(reloadTrigger) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setWebView(this)
                            // Basic Settings
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

                            // Setup JS Interface Bridge
                            addJavascriptInterface(
                                PdfAndroidBridge(
                                    onPageChanged = { page, total ->
                                        currentPage = page
                                        totalPages = total
                                    },
                                    onScaleChanged = { scale ->
                                        currentScale = scale
                                    },
                                    onSearchMatchesChanged = { current, total ->
                                        currentMatch = current
                                        totalMatches = total
                                    }
                                ),
                                "AndroidBridge"
                            )

                            // Configure Clients
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)

                                    // 1. Inject CSS to hide standard PDF.js toolbar completely and make the document full-bleed
                                    val css = """
                                        #toolbarContainer, .toolbar, #sidebarContainer, #secondaryToolbar { display: none !important; }
                                        #viewerContainer { top: 0 !important; bottom: 0 !important; }
                                    """.trimIndent()

                                    val styleInjection = """
                                        var style = document.createElement('style');
                                        style.type = 'text/css';
                                        style.innerHTML = `$css`;
                                        document.head.appendChild(style);
                                    """.trimIndent()
                                    view?.evaluateJavascript(styleInjection, null)

                                    // 2. Inject Event Listeners for the Compose bridge once PDF.js is fully initialized
                                    val bridgeSetup = """
                                        if (typeof PDFViewerApplication !== 'undefined') {
                                            PDFViewerApplication.initializedPromise.then(() => {
                                                // Page changed listener
                                                PDFViewerApplication.eventBus.on('pagechanging', (e) => {
                                                    AndroidBridge.onPageChanged(e.pageNumber, e.pagesCount);
                                                });
                                                // Zoom / scale changed listener
                                                PDFViewerApplication.eventBus.on('scalechanging', (e) => {
                                                    AndroidBridge.onScaleChanged(e.scale);
                                                });
                                                // Find/Search match counts update listener
                                                PDFViewerApplication.eventBus.on('updatefindmatchescount', (e) => {
                                                    AndroidBridge.onSearchMatchesChanged(e.matchesCount.current, e.matchesCount.total);
                                                });
                                            });
                                        }
                                    """.trimIndent()
                                    view?.evaluateJavascript(bridgeSetup, null)
                                }
                            }

                            // Load Viewer pointing to local temp.pdf copied inside the cache directory
                            loadUrl("file:///android_asset/pdfjs/web/viewer.html?file=file://${ctx.cacheDir.absolutePath}/temp.pdf")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // "Go to page" Jump Dialog
    if (showGoToPageDialog) {
        var pageInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGoToPageDialog = false },
            title = { Text("Go to Page") },
            text = {
                Column {
                    Text("Enter page number (1 to $totalPages):")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { pageInput = it.filter { char -> char.isDigit() } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("page_number_input")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetPage = pageInput.toIntOrNull()
                        if (targetPage != null && targetPage in 1..totalPages) {
                            onGoToPage(targetPage)
                        } else {
                            Toast.makeText(context, "Invalid page number", Toast.LENGTH_SHORT).show()
                        }
                        showGoToPageDialog = false
                    },
                    modifier = Modifier.testTag("confirm_goto_page_button")
                ) {
                    Text("Go")
                }
            },
            dismissButton = {
                TextButton(onClick = { showGoToPageDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Document Info Dialog
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Document Info") },
            text = {
                Column {
                    Text("Name: $pdfName", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Total Pages: $totalPages")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Current Zoom: ${(currentScale * 100).toInt()}%")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("View Mode: ${if (isHorizontalScroll) "Horizontal Scroll" else "Vertical Scroll"}")
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

// Helper to copy selected Uri contents to the internal temp cache
private fun copyUriToCache(context: Context, uri: Uri): Boolean {
    return try {
        val cacheFile = File(context.cacheDir, "temp.pdf")
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(cacheFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
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
