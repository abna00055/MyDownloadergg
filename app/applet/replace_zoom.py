import os

path = '/app/applet/app/src/main/java/com/example/MainActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

target = """                                                 var pagesToScale = [];

                                                 document.addEventListener('touchstart', function(e) {
                                                     if (e.touches.length === 2) {
                                                         isPinching = true;
                                                         var t0 = e.touches[0];
                                                         var t1 = e.touches[1];
                                                         initialPinchDist = Math.hypot(
                                                             t0.clientX - t1.clientX,
                                                             t0.clientY - t1.clientY
                                                         );
                                                         if (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.pdfViewer) {
                                                             initialPinchScale = PDFViewerApplication.pdfViewer.currentScale || 1.0;
                                                             var activePageNum = PDFViewerApplication.pdfViewer.currentPageNumber || 1;
                                                             pagesToScale = [];
                                                             [-1, 0, 1].forEach(function(offset) {
                                                                 var pNum = activePageNum + offset;
                                                                 if (pNum >= 1 && pNum <= PDFViewerApplication.pagesCount) {
                                                                     var pEl = document.querySelector('.page[data-page-number="' + pNum + '"]');
                                                                     if (pEl) {
                                                                         pagesToScale.push(pEl);
                                                                     }
                                                                 }
                                                             });
                                                         }
                                                         pinchFactor = 1.0;
                                                         e.preventDefault();
                                                     } else if (e.touches.length === 1) {
                                                         var touch = e.touches[0];
                                                         startTouchX = touch.clientX;
                                                         startTouchY = touch.clientY;
                                                     }
                                                 }, { passive: false });

                                                 document.addEventListener('touchmove', function(e) {
                                                     if (e.touches.length === 2 && isPinching) {
                                                         e.preventDefault();
                                                         var t0 = e.touches[0];
                                                         var t1 = e.touches[1];
                                                         var currentDist = Math.hypot(
                                                             t0.clientX - t1.clientX,
                                                             t0.clientY - t1.clientY
                                                         );
                                                         if (initialPinchDist > 0) {
                                                             pinchFactor = currentDist / initialPinchDist;
                                                             if (pinchFactor < 0.4) pinchFactor = 0.4;
                                                             if (pinchFactor > 4.0) pinchFactor = 4.0;
                                                             
                                                             pagesToScale.forEach(function(page) {
                                                                 page.style.transform = 'scale(' + pinchFactor + ')';
                                                                 page.style.transformOrigin = 'center center';
                                                                 page.style.zIndex = '9999';
                                                             });
                                                         }
                                                     }
                                                 }, { passive: false });

                                                 document.addEventListener('touchend', function(e) {
                                                     if (isPinching && e.touches.length < 2) {
                                                         isPinching = false;
                                                         
                                                         pagesToScale.forEach(function(page) {
                                                             page.style.transform = '';
                                                             page.style.transformOrigin = '';
                                                             page.style.zIndex = '';
                                                         });
                                                         
                                                         if (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.pdfViewer && pinchFactor !== 1.0) {
                                                             var newScale = initialPinchScale * pinchFactor;
                                                             var minS = window.initialPdfScale || window.minPdfScale || 0.5;
                                                             var maxS = 4.0;
                                                             
                                                             if (pinchFactor < 1.0 && newScale <= minS * 1.05) {
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
                                                             }
                                                         }
                                                         pinchFactor = 1.0;
                                                         pagesToScale = [];
                                                     }"""

replacement = """

                                                 document.addEventListener('touchstart', function(e) {
                                                     if (e.touches.length === 2) {
                                                         isPinching = true;
                                                         var t0 = e.touches[0];
                                                         var t1 = e.touches[1];
                                                         initialPinchDist = Math.hypot(
                                                             t0.clientX - t1.clientX,
                                                             t0.clientY - t1.clientY
                                                         );
                                                         if (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.pdfViewer) {
                                                             initialPinchScale = PDFViewerApplication.pdfViewer.currentScale || 1.0;
                                                         }
                                                         
                                                         var viewer = document.getElementById('viewer');
                                                         if (viewer) {
                                                             var rect = viewer.getBoundingClientRect();
                                                             var midX = (t0.clientX + t1.clientX) / 2 - rect.left;
                                                             var midY = (t0.clientY + t1.clientY) / 2 - rect.top;
                                                             viewer.style.transformOrigin = midX + 'px ' + midY + 'px';
                                                             viewer.style.transition = 'none';
                                                         }
                                                         
                                                         pinchFactor = 1.0;
                                                         e.preventDefault();
                                                     } else if (e.touches.length === 1) {
                                                         var touch = e.touches[0];
                                                         startTouchX = touch.clientX;
                                                         startTouchY = touch.clientY;
                                                     }
                                                 }, { passive: false });

                                                 document.addEventListener('touchmove', function(e) {
                                                     if (e.touches.length === 2 && isPinching) {
                                                         e.preventDefault();
                                                         var t0 = e.touches[0];
                                                         var t1 = e.touches[1];
                                                         var currentDist = Math.hypot(
                                                             t0.clientX - t1.clientX,
                                                             t0.clientY - t1.clientY
                                                         );
                                                         if (initialPinchDist > 0) {
                                                             pinchFactor = currentDist / initialPinchDist;
                                                             if (pinchFactor < 0.4) pinchFactor = 0.4;
                                                             if (pinchFactor > 4.0) pinchFactor = 4.0;
                                                             
                                                             var viewer = document.getElementById('viewer');
                                                             if (viewer) {
                                                                 viewer.style.transform = 'scale(' + pinchFactor + ')';
                                                             }
                                                         }
                                                     }
                                                 }, { passive: false });

                                                 document.addEventListener('touchend', function(e) {
                                                     if (isPinching && e.touches.length < 2) {
                                                         isPinching = false;
                                                         
                                                         var viewer = document.getElementById('viewer');
                                                         if (viewer) {
                                                             viewer.style.transform = '';
                                                             viewer.style.transformOrigin = '';
                                                             viewer.style.transition = '';
                                                         }
                                                         
                                                         if (typeof PDFViewerApplication !== 'undefined' && PDFViewerApplication.pdfViewer && pinchFactor !== 1.0) {
                                                             var newScale = initialPinchScale * pinchFactor;
                                                             var minS = window.initialPdfScale || window.minPdfScale || 0.5;
                                                             var maxS = 4.0;
                                                             
                                                             if (pinchFactor < 1.0 && newScale <= minS * 1.05) {
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
                                                             }
                                                         }
                                                         pinchFactor = 1.0;
                                                     }"""

norm_content = content.replace('\r\n', '\n')
norm_target = target.replace('\r\n', '\n')
norm_replacement = replacement.replace('\r\n', '\n')

if norm_target in norm_content:
    print("Found match! Replacing...")
    new_content = norm_content.replace(norm_target, norm_replacement)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(new_content)
    print("Successfully replaced!")
else:
    print("Target not found.")
