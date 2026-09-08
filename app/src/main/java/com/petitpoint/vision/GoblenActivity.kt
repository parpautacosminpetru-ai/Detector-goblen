package com.petitpoint.vision

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.petitpoint.vision.model.BuiltInGoblenProject
import com.petitpoint.vision.model.GridCell
import com.petitpoint.vision.model.PatternGrid
import com.petitpoint.vision.ui.PatternOverlayView
import com.petitpoint.vision.ui.PatternPickerView
import com.petitpoint.vision.ui.ScannerOverlayView
import com.petitpoint.vision.vision.BuiltInSymbolCatalog
import com.petitpoint.vision.vision.ChartGridCropper
import com.petitpoint.vision.vision.GrayFrame
import com.petitpoint.vision.vision.GridDetector
import com.petitpoint.vision.vision.SymbolFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Modul dedicat goblenului real: 200 x 250 ochiuri, 16 pagini, 59 cartele.
 *
 * Fluxul corect este global: utilizatorul caută NUMĂRUL cartelei, aplicația caută SIMBOLUL
 * în toate paginile încărcate și construiește un singur traseu de jos în sus. Cifra nu este
 * desenată niciodată pe pânză; se desenează forma simbolului tipărit.
 */
class GoblenActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var scannerOverlay: ScannerOverlayView
    private lateinit var overlayView: PatternOverlayView
    private lateinit var statusText: TextView
    private lateinit var pageText: TextView
    private lateinit var scanText: TextView
    private lateinit var codeInput: EditText
    private lateinit var symbolPreview: ImageView
    private lateinit var symbolText: TextView
    private lateinit var guideText: TextView
    private lateinit var prevTargetButton: Button
    private lateinit var nextTargetButton: Button

    private var camera: Camera? = null
    private var pageNumber = 1
    private var pendingImportPage = 1
    private var pageBitmap: Bitmap? = null
    private var pageGrid: PatternGrid? = null
    private var pageLoadGeneration = 0

    private var selectedCode: Int? = null
    private var selectedSymbolBitmap: Bitmap? = null
    private var searchJob: Job? = null

    private val matchesByPage = LinkedHashMap<Int, List<GridCell>>()
    private var navigationTargets: List<GridCell> = emptyList()
    private var navigationIndex = -1
    private var rowDirections: Map<Int, Int> = emptyMap()

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val gridDetector = GridDetector()
    private var scannerFrameCounter = 0

    @Volatile private var latestGridConfidence = 0f
    @Volatile private var latestVerticalLines: List<Float> = emptyList()
    @Volatile private var latestHorizontalLines: List<Float> = emptyList()
    @Volatile private var latestCellWidthView = 0f
    @Volatile private var latestCellHeightView = 0f
    @Volatile private var awaitingAnchorTap = false

    private val prefs by lazy { getSharedPreferences("custom_goblen_project", MODE_PRIVATE) }
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else status("Camera este necesară pentru suprapunerea pe pânză.")
    }

    private val pagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            prefs.edit().putString("page_uri_$pendingImportPage", uri.toString()).apply()
            pageNumber = pendingImportPage
            activatePage(pageNumber, keepGuide = navigationTargets.isNotEmpty())
            selectedCode?.let { searchCodeAcrossProject(it) }
        }
    }

    private val allPagesPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) importManyPages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_goblen)

        previewView = findViewById(R.id.previewView)
        scannerOverlay = findViewById(R.id.scannerOverlay)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        pageText = findViewById(R.id.pageText)
        scanText = findViewById(R.id.scanText)
        codeInput = findViewById(R.id.codeInput)
        symbolPreview = findViewById(R.id.symbolPreview)
        symbolText = findViewById(R.id.symbolText)
        guideText = findViewById(R.id.guideText)
        prevTargetButton = findViewById(R.id.prevTargetButton)
        nextTargetButton = findViewById(R.id.nextTargetButton)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        findViewById<Button>(R.id.prevPageButton).setOnClickListener { changePage(-1) }
        findViewById<Button>(R.id.nextPageButton).setOnClickListener { changePage(1) }
        findViewById<Button>(R.id.importPageButton).setOnClickListener { importCurrentPage() }
        findViewById<Button>(R.id.importAllPagesButton).setOnClickListener {
            allPagesPickerLauncher.launch(arrayOf("image/*"))
        }
        findViewById<Button>(R.id.searchCodeButton).setOnClickListener { searchCode() }
        findViewById<Button>(R.id.learnSymbolButton).setOnClickListener { learnCurrentSymbol() }
        findViewById<Button>(R.id.calibrateButton).setOnClickListener { beginOneTapAlignment() }
        findViewById<Button>(R.id.clearCalibrationButton).setOnClickListener {
            awaitingAnchorTap = false
            overlayView.clearCalibration()
            status("Alinierea a fost ștearsă. Apasă ALINIAZĂ și atinge ochiul țintă.")
        }
        prevTargetButton.setOnClickListener { moveTarget(-1) }
        nextTargetButton.setOnClickListener { moveTarget(1) }

        codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                searchCode()
                true
            } else {
                false
            }
        }

        setupCameraGestures()
        requestCameraIfNeeded()
        activatePage(pageNumber, keepGuide = false)
        resetGuide()
    }

    private fun requestCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(analysisExecutor) { image ->
                        try {
                            processScannerFrame(GrayFrame.from(image))
                        } finally {
                            image.close()
                        }
                    }
                }

            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            status("Camera este gata. Liniile verzi trebuie să urmărească ochiurile pânzei.")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processScannerFrame(frame: GrayFrame) {
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        scannerFrameCounter++
        if (scannerFrameCounter % 3 != 0) return

        val detection = gridDetector.detect(frame)
        val vertical = detection.verticalLines
            .map { x -> frame.frameToView(PointF(x, frame.height / 2f), viewWidth, viewHeight).x }
            .filter { it in 0f..viewWidth.toFloat() }
            .sorted()
        val horizontal = detection.horizontalLines
            .map { y -> frame.frameToView(PointF(frame.width / 2f, y), viewWidth, viewHeight).y }
            .filter { it in 0f..viewHeight.toFloat() }
            .sorted()

        latestGridConfidence = detection.confidence
        latestVerticalLines = vertical
        latestHorizontalLines = horizontal
        latestCellWidthView = medianSpacing(vertical)
        latestCellHeightView = medianSpacing(horizontal)

        scannerOverlay.post {
            scannerOverlay.setDetectedGrid(vertical, horizontal, detection.confidence)
            val confidence = (detection.confidence * 100f).toInt()
            scanText.text = when {
                detection.confidence >= 0.45f && vertical.size >= 3 && horizontal.size >= 3 ->
                    "GRILĂ: $confidence% · ${"%.1f".format(latestCellWidthView)}×${"%.1f".format(latestCellHeightView)} px"
                detection.confidence >= 0.18f ->
                    "GRILĂ: $confidence% · apropie sau fă zoom până liniile verzi cad pe ochiuri"
                else ->
                    "GRILĂ: caut… mărește pânza până se văd clar ochiurile"
            }
        }
    }

    private fun medianSpacing(lines: List<Float>): Float {
        if (lines.size < 2) return 0f
        val diffs = lines.zipWithNext { a, b -> b - a }
            .filter { it > 3f && it.isFinite() }
            .sorted()
        if (diffs.isEmpty()) return 0f
        val mid = diffs.size / 2
        return if (diffs.size % 2 == 1) diffs[mid] else (diffs[mid - 1] + diffs[mid]) * 0.5f
    }

    private fun setupCameraGestures() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val active = camera ?: return false
                    val state = active.cameraInfo.zoomState.value ?: return false
                    val next = (state.zoomRatio * detector.scaleFactor)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    active.cameraControl.setZoomRatio(next)
                    return true
                }
            }
        )

        val tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (!awaitingAnchorTap) return false
                    alignAt(e.x, e.y)
                    return true
                }
            }
        )

        previewView.setOnTouchListener { _, event ->
            val scaleHandled = scaleDetector.onTouchEvent(event)
            val tapHandled = tapDetector.onTouchEvent(event)
            scaleHandled || tapHandled || awaitingAnchorTap
        }
    }

    private fun changePage(delta: Int) {
        pageNumber = (pageNumber + delta).coerceIn(1, BuiltInGoblenProject.PAGE_COUNT)
        activatePage(pageNumber, keepGuide = navigationTargets.isNotEmpty())
    }

    private fun importCurrentPage() {
        pendingImportPage = pageNumber
        pagePickerLauncher.launch(arrayOf("image/*"))
    }

    private fun importManyPages(uris: List<Uri>) {
        lifecycleScope.launch {
            var recognized = 0
            val duplicates = HashSet<Int>()
            for ((index, uri) in uris.withIndex()) {
                persistReadPermission(uri)
                status("Identific fotografia ${index + 1}/${uris.size} după textul «Page N»…")
                val number = recognizePrintedPageNumber(uri)
                if (number != null && number in 1..16 && duplicates.add(number)) {
                    prefs.edit().putString("page_uri_$number", uri.toString()).apply()
                    recognized++
                }
            }
            status("Am identificat $recognized pagini din ${uris.size}. Poți selecta din nou doar fotografiile lipsă.")
            activatePage(pageNumber, keepGuide = navigationTargets.isNotEmpty())
            selectedCode?.let { searchCodeAcrossProject(it) }
        }
    }

    private suspend fun recognizePrintedPageNumber(uri: Uri): Int? {
        return try {
            val image = InputImage.fromFilePath(this, uri)
            val text = suspendCancellableCoroutine { continuation ->
                textRecognizer.process(image)
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            Regex("(?i)\\bPage\\s*([0-9]{1,2})\\b")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun activatePage(number: Int, keepGuide: Boolean) {
        pageNumber = number.coerceIn(1, BuiltInGoblenProject.PAGE_COUNT)
        val page = BuiltInGoblenProject.page(pageNumber)
        val loaded = prefs.getString("page_uri_$pageNumber", null) != null
        pageText.text = "Pagina ${page.number}/16 · R${page.startRow + 1}-${page.endRowInclusive + 1} · C${page.startCol + 1}-${page.endColInclusive + 1} · ${if (loaded) "ÎNCĂRCATĂ" else "LIPSĂ"}"

        awaitingAnchorTap = false
        overlayView.configureGrid(BuiltInGoblenProject.TOTAL_ROWS, BuiltInGoblenProject.TOTAL_COLS, page.region)
        overlayView.setTargets(matchesByPage[pageNumber].orEmpty(), selectedSymbolBitmap)

        if (!keepGuide) {
            resetGuide()
        } else {
            val focused = navigationTargets.getOrNull(navigationIndex)
            if (focused != null && pageForCell(focused) == pageNumber) {
                overlayView.setFocusedCell(focused, rowDirections[focused.row] ?: 1)
            } else {
                overlayView.setFocusedCell(null, 1)
            }
        }
        loadPageForLearning(pageNumber)
    }

    private fun loadPageForLearning(number: Int) {
        val generation = ++pageLoadGeneration
        val page = BuiltInGoblenProject.page(number)
        val saved = prefs.getString("page_uri_$number", null)
        if (saved == null) {
            recyclePage()
            if (selectedCode == null) {
                status("Pagina $number nu este încă încărcată. Poți folosi «ÎNCARCĂ 16 PAGINI».")
            }
            return
        }

        lifecycleScope.launch {
            val loaded = loadCroppedPage(number, Uri.parse(saved)) ?: return@launch
            if (generation != pageLoadGeneration || number != pageNumber) {
                loaded.bitmap.recycle()
                return@launch
            }
            recyclePage()
            pageBitmap = loaded.bitmap
            pageGrid = PatternGrid(loaded.bitmap, page.rowCount, page.colCount)
        }
    }

    private suspend fun loadCroppedPage(number: Int, uri: Uri): ChartGridCropper.Result? {
        val page = BuiltInGoblenProject.page(number)
        return withContext(Dispatchers.IO) {
            val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
            try {
                ChartGridCropper.crop(source, page.rowCount, page.colCount)
            } catch (_: Throwable) {
                null
            } finally {
                if (!source.isRecycled) source.recycle()
            }
        }
    }

    private fun searchCode() {
        val code = codeInput.text?.toString()?.trim()?.toIntOrNull()
        if (code == null || code !in 1..59) {
            status("Scrie numărul cartelei între 1 și 59.")
            return
        }
        selectedCode = code
        val definition = BuiltInSymbolCatalog.definition(code) ?: return
        setDisplayedSymbol(code)
        symbolText.text = "Cartela $code · ${definition.name} · pe pânză apare SIMBOLUL"
        searchCodeAcrossProject(code)
    }

    private fun searchCodeAcrossProject(code: Int) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            matchesByPage.clear()
            resetGuide()
            overlayView.setTargets(emptyList(), selectedSymbolBitmap)

            val loadedPages = (1..16).filter { prefs.getString("page_uri_$it", null) != null }
            if (loadedPages.isEmpty()) {
                status("Cartela $code este aleasă, dar nu ai încărcat nicio pagină. Apasă «ÎNCARCĂ 16 PAGINI».")
                return@launch
            }

            val trained = readSavedFingerprint(code)
            val fingerprint = trained ?: withContext(Dispatchers.Default) { BuiltInSymbolCatalog.fingerprint(code) }
            val threshold = if (trained != null) 0.22f else 0.28f

            var processed = 0
            for (pageNo in loadedPages) {
                processed++
                status("Cartela $code: caut simbolul în toate paginile… $processed/${loadedPages.size}")
                val uriText = prefs.getString("page_uri_$pageNo", null) ?: continue
                val result = loadCroppedPage(pageNo, Uri.parse(uriText)) ?: continue
                try {
                    val page = BuiltInGoblenProject.page(pageNo)
                    val grid = PatternGrid(result.bitmap, page.rowCount, page.colCount)
                    val local = withContext(Dispatchers.Default) {
                        grid.matchingFingerprint(fingerprint, maxDistance = threshold)
                    }
                    if (local.isNotEmpty()) {
                        matchesByPage[pageNo] = local.map {
                            GridCell(page.startRow + it.row, page.startCol + it.col)
                        }
                    }
                } finally {
                    if (!result.bitmap.isRecycled) result.bitmap.recycle()
                }
            }

            val allMatches = matchesByPage.values.flatten()
            if (allMatches.isEmpty()) {
                overlayView.setTargets(emptyList(), selectedSymbolBitmap)
                status(
                    if (trained != null) {
                        "Cartela $code nu a fost găsită în paginile încărcate. Verifică dacă lipsesc pagini."
                    } else {
                        "Forma implicită pentru cartela $code nu se potrivește suficient cu tiparul. Mergi la o pagină unde îl vezi, apasă «ÎNVAȚĂ SIMBOLUL» și atinge o apariție. Se face o singură dată."
                    }
                )
                return@launch
            }

            prepareGuide(allMatches)
            val firstCell = navigationTargets.firstOrNull()
            if (firstCell != null) {
                val firstPage = pageForCell(firstCell)
                activatePage(firstPage, keepGuide = true)
                applyFocus()
            }
            status("Cartela $code: ${allMatches.size} poziții găsite în ${matchesByPage.size} pagini. Traseul este de jos în sus; pagina se schimbă singură.")
        }
    }

    private fun learnCurrentSymbol() {
        val code = selectedCode
        val grid = pageGrid
        if (code == null) {
            status("Caută mai întâi cartela pe care vrei s-o învăț.")
            return
        }
        if (grid == null) {
            status("Încarcă fotografia unei pagini în care vezi simbolul cartelei $code.")
            return
        }

        val picker = PatternPickerView(this).apply { setGrid(grid) }
        val dialog = Dialog(this).apply { setContentView(picker) }
        picker.onCellSelected = { cell ->
            dialog.dismiss()
            val fingerprint = SymbolFingerprint.fromRect(grid.bitmap, grid.cellRect(cell))
            saveFingerprint(code, fingerprint)
            val exactSymbol = grid.symbolBitmap(cell)
            saveSymbolBitmap(code, exactSymbol)
            selectedSymbolBitmap?.takeIf { !it.isRecycled }?.recycle()
            selectedSymbolBitmap = exactSymbol
            symbolPreview.setImageBitmap(exactSymbol)
            status("Cartela $code: am memorat forma exactă tipărită. Reanalizez automat toate paginile.")
            searchCodeAcrossProject(code)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun setDisplayedSymbol(code: Int) {
        selectedSymbolBitmap?.takeIf { !it.isRecycled }?.recycle()
        selectedSymbolBitmap = loadSavedSymbol(code) ?: BuiltInSymbolCatalog.render(code)
        symbolPreview.setImageBitmap(selectedSymbolBitmap)
        overlayView.setTargets(matchesByPage[pageNumber].orEmpty(), selectedSymbolBitmap)
    }

    private fun prepareGuide(cells: List<GridCell>) {
        if (cells.isEmpty()) {
            resetGuide()
            return
        }

        val rows = cells.groupBy { it.row }.keys.sortedDescending()
        val ordered = ArrayList<GridCell>(cells.size)
        val dirs = HashMap<Int, Int>()
        for ((index, row) in rows.withIndex()) {
            val direction = if (index % 2 == 0) 1 else -1
            dirs[row] = direction
            val rowCells = cells.filter { it.row == row }
            ordered.addAll(if (direction > 0) rowCells.sortedBy { it.col } else rowCells.sortedByDescending { it.col })
        }
        navigationTargets = ordered
        rowDirections = dirs
        navigationIndex = 0
    }

    private fun moveTarget(delta: Int) {
        if (navigationTargets.isEmpty()) return
        val next = (navigationIndex + delta).coerceIn(0, navigationTargets.lastIndex)
        if (next == navigationIndex) return
        navigationIndex = next
        applyFocus()
    }

    private fun applyFocus() {
        val cell = navigationTargets.getOrNull(navigationIndex)
        if (cell == null) {
            overlayView.setFocusedCell(null, 1)
            resetGuide()
            return
        }

        val targetPage = pageForCell(cell)
        if (targetPage != pageNumber) {
            activatePage(targetPage, keepGuide = true)
        }

        val direction = rowDirections[cell.row] ?: 1
        overlayView.setTargets(matchesByPage[pageNumber].orEmpty(), selectedSymbolBitmap)
        overlayView.setFocusedCell(cell, direction)
        val arrow = if (direction > 0) "→" else "←"
        guideText.text = "${navigationIndex + 1}/${navigationTargets.size} · P$pageNumber · R${cell.row + 1} C${cell.col + 1} · $arrow"
        prevTargetButton.isEnabled = navigationIndex > 0
        nextTargetButton.isEnabled = navigationIndex < navigationTargets.lastIndex
    }

    private fun pageForCell(cell: GridCell): Int {
        val rowBand = when (cell.row) {
            in 0..69 -> 0
            in 70..139 -> 1
            in 140..209 -> 2
            else -> 3
        }
        val colBand = (cell.col / 50).coerceIn(0, 3)
        return rowBand * 4 + colBand + 1
    }

    private fun resetGuide() {
        navigationTargets = emptyList()
        navigationIndex = -1
        rowDirections = emptyMap()
        if (::guideText.isInitialized) guideText.text = "Alege cartela"
        if (::prevTargetButton.isInitialized) prevTargetButton.isEnabled = false
        if (::nextTargetButton.isInitialized) nextTargetButton.isEnabled = false
        if (::overlayView.isInitialized) overlayView.setFocusedCell(null, 1)
    }

    private fun beginOneTapAlignment() {
        val cell = navigationTargets.getOrNull(navigationIndex)
        if (cell == null) {
            status("Caută o cartelă; aplicația va alege singură prima poziție de jos.")
            return
        }
        if (latestGridConfidence < 0.18f || latestCellWidthView < 5f || latestCellHeightView < 5f) {
            status("Nu văd încă ochiurile suficient de clar. Fă zoom până apar liniile verzi, apoi apasă din nou ALINIAZĂ.")
            return
        }
        awaitingAnchorTap = true
        status("ALINIERE: atinge o singură dată pe ecran ochiul fizic corespunzător poziției R${cell.row + 1} C${cell.col + 1}.")
    }

    private fun alignAt(rawX: Float, rawY: Float) {
        val cell = navigationTargets.getOrNull(navigationIndex) ?: return
        val page = BuiltInGoblenProject.page(pageNumber)
        val cellW = latestCellWidthView
        val cellH = latestCellHeightView
        if (cellW < 5f || cellH < 5f) {
            awaitingAnchorTap = false
            status("Am pierdut grila. Fă zoom până reapar liniile verzi și repetă ALINIAZĂ.")
            return
        }

        val anchorX = nearestCellCenter(rawX, latestVerticalLines) ?: rawX
        val anchorY = nearestCellCenter(rawY, latestHorizontalLines) ?: rawY

        val localCenterCol = (cell.col + 0.5f) - page.startCol.toFloat()
        val localCenterRow = (cell.row + 0.5f) - page.startRow.toFloat()
        val left = anchorX - localCenterCol * cellW
        val top = anchorY - localCenterRow * cellH
        val right = left + page.colCount * cellW
        val bottom = top + page.rowCount * cellH

        overlayView.setTrackedCalibrationPoints(
            listOf(
                PointF(left, top),
                PointF(right, top),
                PointF(right, bottom),
                PointF(left, bottom)
            )
        )
        awaitingAnchorTap = false
        applyFocus()
        status("ALINIAT. Simbolul cartelei ${selectedCode ?: ""} este acum pus pe ochiurile calculate; ◀/▶ te conduce prin traseu.")
    }

    private fun nearestCellCenter(value: Float, lines: List<Float>): Float? {
        if (lines.size < 2) return null
        var best: Float? = null
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until lines.lastIndex) {
            val gap = lines[i + 1] - lines[i]
            if (gap <= 3f) continue
            val center = (lines[i] + lines[i + 1]) * 0.5f
            val distance = abs(center - value)
            if (distance < bestDistance) {
                bestDistance = distance
                best = center
            }
        }
        return best
    }

    private fun saveFingerprint(code: Int, fingerprint: BooleanArray) {
        val encoded = buildString(fingerprint.size) { fingerprint.forEach { append(if (it) '1' else '0') } }
        prefs.edit().putString("fingerprint_v2_$code", encoded).apply()
    }

    private fun readSavedFingerprint(code: Int): BooleanArray? {
        val encoded = prefs.getString("fingerprint_v2_$code", null) ?: return null
        if (encoded.isEmpty()) return null
        return BooleanArray(encoded.length) { encoded[it] == '1' }
    }

    private fun symbolFile(code: Int): File = File(filesDir, "goblen_symbol_$code.png")

    private fun saveSymbolBitmap(code: Int, bitmap: Bitmap) {
        try {
            FileOutputStream(symbolFile(code)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Throwable) {
            // Fingerprintul exact rămâne memorat chiar dacă preview-ul nu poate fi scris.
        }
    }

    private fun loadSavedSymbol(code: Int): Bitmap? {
        val file = symbolFile(code)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Unele galerii nu oferă permisiune persistentă.
        }
    }

    private fun recyclePage() {
        pageGrid = null
        pageBitmap?.takeIf { !it.isRecycled }?.recycle()
        pageBitmap = null
    }

    private fun status(message: String) {
        statusText.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
        analysisExecutor.shutdownNow()
        textRecognizer.close()
        recyclePage()
        selectedSymbolBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}
