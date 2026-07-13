import os

file_path = "/app/src/main/java/com/example/MainActivity.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Clean up event listeners and forceRenderCheck
target_listeners = """                                                function forceRenderCheck() {
                                                    try {
                                                        if (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.pdfViewer) {
                                                            PDFViewerApplication.pdfViewer.update();
                                                        }
                                                    } catch (err) {
                                                        console.error("forceRenderCheck failed", err);
                                                    }
                                                }
                                                
                                                PDFViewerApplication.eventBus.on('pagechanging', function(e) {
                                                    if (typeof AndroidBridge !== 'undefined') {
                                                        var total = e.pagesCount || (typeof PDFViewerApplication !== 'undefined' ? PDFViewerApplication.pagesCount : 0) || 0;
                                                        AndroidBridge.onPageChanged(e.pageNumber, total);
                                                    }
                                                    forceRenderCheck();
                                                    setTimeout(forceRenderCheck, 150);
                                                    setTimeout(forceRenderCheck, 400);
                                                });
                                                PDFViewerApplication.eventBus.on('scalechanging', function(e) {
                                                    if (typeof AndroidBridge !== 'undefined' && e.scale) {
                                                        if (window.minPdfScale && e.scale < window.minPdfScale) {
                                                            PDFViewerApplication.pdfViewer.currentScale = window.minPdfScale;
                                                        } else {
                                                            AndroidBridge.onScaleChanged(e.scale);
                                                        }
                                                    }
                                                    forceRenderCheck();
                                                });
                                                PDFViewerApplication.eventBus.on('pagerendered', function(e) {
                                                    if (!window.minPdfScale && PDFViewerApplication.pdfViewer && PDFViewerApplication.pdfViewer.currentScale) {
                                                        window.minPdfScale = PDFViewerApplication.pdfViewer.currentScale;
                                                    }
                                                    if (!window.initialPdfScale && PDFViewerApplication.pdfViewer && PDFViewerApplication.pdfViewer.currentScale) {
                                                        window.initialPdfScale = PDFViewerApplication.pdfViewer.currentScale;
                                                    }
                                                    forceRenderCheck();
                                                });
                                                
                                                var viewerContainer = document.getElementById('viewerContainer');
                                                if (viewerContainer) {
                                                    viewerContainer.addEventListener('scroll', function() {
                                                        forceRenderCheck();
                                                    });
                                                }
                                                
                                                window.addEventListener('resize', function() {
                                                    window.minPdfScale = null;
                                                    forceRenderCheck();
                                                });
                                                
                                                setTimeout(forceRenderCheck, 150);
                                                setTimeout(forceRenderCheck, 600);
                                                setTimeout(forceRenderCheck, 1500);"""

replacement_listeners = """                                                PDFViewerApplication.eventBus.on('pagechanging', function(e) {
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
                                                });"""

# 2. handleDoubleTap cleanup and scrollLeft = 0 reset
target_double_tap = """                                                function handleDoubleTap() {
                                                    if (typeof PDFViewerApplication === 'undefined' || !PDFViewerApplication.pdfViewer) return;
                                                    var viewer = PDFViewerApplication.pdfViewer;
                                                    var current = viewer.currentScale;
                                                    
                                                    var baseScale = window.initialPdfScale || window.minPdfScale || 1.0;
                                                    var targetScale = baseScale * 1.5;
                                                    
                                                    if (Math.abs(current - targetScale) < 0.15 || current > baseScale * 1.15) {
                                                        if (window.isHorizontalScroll) {
                                                            viewer.currentScaleValue = 'page-fit';
                                                        } else {
                                                            viewer.currentScaleValue = 'page-width';
                                                        }
                                                    } else {
                                                        viewer.currentScale = targetScale;
                                                    }
                                                    forceRenderCheck();
                                                    setTimeout(forceRenderCheck, 150);
                                                    setTimeout(forceRenderCheck, 400);
                                                }"""

replacement_double_tap = """                                                function handleDoubleTap() {
                                                    if (typeof PDFViewerApplication === 'undefined' || !PDFViewerApplication.pdfViewer) return;
                                                    var viewer = PDFViewerApplication.pdfViewer;
                                                    var current = viewer.currentScale;
                                                    
                                                    var baseScale = window.initialPdfScale || window.minPdfScale || 1.0;
                                                    var targetScale = baseScale * 1.5;
                                                    
                                                    if (Math.abs(current - targetScale) < 0.15 || current > baseScale * 1.15) {
                                                        if (window.isHorizontalScroll) {
                                                            viewer.currentScaleValue = 'page-fit';
                                                        } else {
                                                            viewer.currentScaleValue = 'page-width';
                                                        }
                                                        var container = document.getElementById('viewerContainer');
                                                        if (container) {
                                                            container.scrollLeft = 0;
                                                        }
                                                    } else {
                                                        viewer.currentScale = targetScale;
                                                    }
                                                }"""

# 3. touchend cleanup and scrollLeft = 0 reset
target_touchend_pinch = """                                                            if (pinchFactor < 1.0 && newScale <= minS * 1.05) {
                                                                if (window.isHorizontalScroll) {
                                                                    PDFViewerApplication.pdfViewer.currentScaleValue = 'page-fit';
                                                                } else {
                                                                    PDFViewerApplication.pdfViewer.currentScaleValue = 'page-width';
                                                                }
                                                            } else {
                                                                if (newScale < minS) newScale = minS;
                                                                if (newScale > maxS) newScale = maxS;
                                                                PDFViewerApplication.pdfViewer.currentScale = newScale;
                                                            }
                                                            forceRenderCheck();
                                                            setTimeout(forceRenderCheck, 150);
                                                            setTimeout(forceRenderCheck, 400);"""

replacement_touchend_pinch = """                                                            if (pinchFactor < 1.0 && newScale <= minS * 1.05) {
                                                                if (window.isHorizontalScroll) {
                                                                    PDFViewerApplication.pdfViewer.currentScaleValue = 'page-fit';
                                                                } else {
                                                                    PDFViewerApplication.pdfViewer.currentScaleValue = 'page-width';
                                                                }
                                                                var container = document.getElementById('viewerContainer');
                                                                if (container) {
                                                                    container.scrollLeft = 0;
                                                                }
                                                            } else {
                                                                if (newScale < minS) newScale = minS;
                                                                if (newScale > maxS) newScale = maxS;
                                                                PDFViewerApplication.pdfViewer.currentScale = newScale;
                                                            }"""

if target_listeners in content:
    content = content.replace(target_listeners, replacement_listeners)
    print("Listeners replaced successfully!")
else:
    print("Warning: Listeners target not found precisely.")

if target_double_tap in content:
    content = content.replace(target_double_tap, replacement_double_tap)
    print("Double tap replaced successfully!")
else:
    print("Warning: Double tap target not found precisely.")

if target_touchend_pinch in content:
    content = content.replace(target_touchend_pinch, replacement_touchend_pinch)
    print("Touchend pinch replaced successfully!")
else:
    print("Warning: Touchend pinch target not found precisely.")

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Patching complete.")
