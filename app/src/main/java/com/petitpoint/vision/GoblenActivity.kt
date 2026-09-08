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
import android.view.ScaleGestureDetector
import android.view.View
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
import com.petitpoint.vision.model.GridRegion
import com.petitpoint.vision.model.PatternGrid
import com.petitpoint.vision.ui.PatternOverlayView
import com.petitpoint.vision.ui.PatternPickerView
import com.petitpoint.vision.vision.BuiltInSymbolCatalog
import com.petitpoint.vision.vision.CanvasCalibrationStore
import com.petitpoint.vision.vision.ChartGridCropper
import com.petitpoint.vision.vision.SymbolFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Modul dedicat goblenului real: 200 x 250 ochiuri, 16 pagini, 59 cartele.
 *
 * Camera arată numai pânza reală. Fotografiile diagramelor sunt folosite în fundal doar pentru
 * a afla coordonatele simbolului. Overlay-ul este global pe toate cele 200 x 250 de ochiuri.
 * Calibrarea nu depinde de detectorul de grilă: utilizatorul atinge o singură dată cele patru
 * colțuri ale pânzei, iar perspectiva rezultată este păstrată și scalată odată cu lupa.
 */
class GoblenActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
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
    private var currentZoomRatio = 1f
    private var calibrationRestoreAttempted = false

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

    private val prefs by lazy { getSharedPreferences("custom_goblen_project", MODE_PRIVATE) }
    private val calibrationStore by lazy { CanvasCalibrationStore(this) }
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

        // v0.10 nu mai folosește grila verde pentru aliniere.
        findViewById<View>(R.id.scannerOverlay).visibility = View.GONE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        // O singură hartă globală: 250 rânduri x 200 coloane.
        overlayView.configureGrid(
            BuiltInGoblenProject.TOTAL_ROWS,
            BuiltInGoblenProject.TOTAL_COLS,
            GridRegion(0, 0, BuiltInGoblenProject.TOTAL_ROWS, BuiltInGoblenProject.TOTAL_COLS)
        )

        overlayView.onCalibrationProgress = { count ->
            val corners = listOf("stânga-sus", "dreapta-sus", "dreapta-jos", "stânga-jos")
            if (count < 4) {
                status("CALIBRARE PÂNZĂ: atinge colțul ${corners[count]} al pânzei.")
            }
        }
        overlayView.onCalibrationComplete = {
            saveCurrentCalibration()
            scanText.text = "PÂNZĂ: CALIBRATĂ · lupa păstrează simbolurile pe poziție"
            overlayView.setTargets(allMatches(), selectedSymbolBitmap)
            applyFocus()
            status("Pânza este calibrată. Acum poți mări cu lupa; simbolurile rămân lipite de pozițiile lor.")
        }

        findViewById<Button>(R.id.prevPageButton).setOnClickListener { changePage(-1) }
        findViewById<Button>(R.id.nextPageButton).setOnClickListener { changePage(1) }
        findViewById<Button>(R.id.importPageButton).setOnClickListener { importCurrentPage() }
        findViewById<Button>(R.id.importAllPagesButton).setOnClickListener {
            allPagesPickerLauncher.launch(arrayOf("image/*"))
        }
        findViewById<Button>(R.id.searchCodeButton).setOnClickListener { searchCode() }
        findViewById<Button>(R.id.learnSymbolButton).setOnClickListener { learnCurrentSymbol() }
        findViewById<Button>(R.id.calibrateButton).setOnClickListener { beginCanvasCalibration() }
        findViewById<Button>(R.id.clearCalibrationButton).setOnClickListener { clearCanvasCalibration() }
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

        setupMagnifier()
        activatePage(pageNumber, keepGuide = false)
        resetGuide()
        requestCameraIfNeeded()
    }

    private fun requestCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)

            val state = camera?.cameraInfo?.zoomState?.value
            currentZoomRatio = state?.zoomRatio ?: 1f
            previewView.post { restoreCalibrationIfAvailable() }

            if (!overlayView.hasCalibration()) {
                status("Camera este gata. Prima dată: încadrează pânza întreagă și apasă CALIBREAZĂ PÂNZA.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Camera funcționează ca lupă; perspectiva overlay-ului se scalează în același timp. */
    private fun setupMagnifier() {
        val detector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(scale: ScaleGestureDetector): Boolean {
                    val active = camera ?: return false
                    val state = active.cameraInfo.zoomState.value ?: return false
                    val oldZoom = currentZoomRatio.coerceAtLeast(0.01f)
                    val nextZoom = (oldZoom * scale.scaleFactor)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    if (kotlin.math.abs(nextZoom - oldZoom) < 0.001f) return true

                    active.cameraControl.setZoomRatio(nextZoom)
                    if (overlayView.hasCalibration()) {
                        val factor = nextZoom / oldZoom
                        overlayView.scaleCalibrationAbout(
                            previewView.width / 2f,
                            previewView.height / 2f,
                            factor
                        )
                    }
                    currentZoomRatio = nextZoom
                    if (overlayView.hasCalibration()) saveCurrentCalibration()
                    scanText.text = "🔎 LUPĂ ${"%.1f".format(nextZoom)}× · simbolurile rămân ancorate"
                    return true
                }
            }
        )
        previewView.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    /**
     * Calibrarea de bază se face la 1x, cu pânza întreagă în cadru.
     * Ordinea colțurilor este aceeași cu homografia PatternOverlayView.
     */
    private fun beginCanvasCalibration() {
        val active = camera
        val state = active?.cameraInfo?.zoomState?.value
        if (active != null && state != null) {
            val targetZoom = 1f.coerceIn(state.minZoomRatio, state.maxZoomRatio)
            active.cameraControl.setZoomRatio(targetZoom)
            currentZoomRatio = targetZoom
        }

        calibrationStore.clear()
        overlayView.beginCalibration()
        scanText.text = "PÂNZĂ: CALIBRARE 4 COLȚURI · fără detector verde"
        status("Încadrează pânza întreagă. Atinge colțul STÂNGA-SUS al pânzei, apoi urmează indicațiile.")
    }

    private fun clearCanvasCalibration() {
        calibrationStore.clear()
        overlayView.clearCalibration()
        calibrationRestoreAttempted = true
        scanText.text = "PÂNZĂ: NECALIBRATĂ · apasă CALIBREAZĂ PÂNZA"
        status("Calibrarea a fost ștearsă. Încadrează pânza întreagă și calibrează din nou cele 4 colțuri.")
    }

    private fun saveCurrentCalibration() {
        if (!overlayView.hasCalibration()) return
        calibrationStore.save(
            overlayView.calibrationPointsSnapshot(),
            previewView.width,
            previewView.height,
            currentZoomRatio
        )
    }

    private fun restoreCalibrationIfAvailable() {
        if (calibrationRestoreAttempted || previewView.width <= 0 || previewView.height <= 0) return
        calibrationRestoreAttempted = true
        val saved = calibrationStore.load(previewView.width, previewView.height)
        if (saved == null) {
            scanText.text = "PÂNZĂ: NECALIBRATĂ · calibrează o dată cele 4 colțuri"
            return
        }

        val active = camera
        val state = active?.cameraInfo?.zoomState?.value
        val restoredZoom = if (state != null) {
            saved.zoomRatio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        } else {
            saved.zoomRatio
        }
        active?.cameraControl?.setZoomRatio(restoredZoom)

        val zoomCorrection = if (saved.zoomRatio > 0.01f) restoredZoom / saved.zoomRatio else 1f
        val points = saved.points.map { PointF(it.x, it.y) }.toMutableList()
        if (kotlin.math.abs(zoomCorrection - 1f) > 0.001f) {
            val cx = previewView.width / 2f
            val cy = previewView.height / 2f
            points.forEach { point ->
                point.x = cx + (point.x - cx) * zoomCorrection
                point.y = cy + (point.y - cy) * zoomCorrection
            }
        }
        currentZoomRatio = restoredZoom
        overlayView.setTrackedCalibrationPoints(points)
        overlayView.setTargets(allMatches(), selectedSymbolBitmap)
        scanText.text = "PÂNZĂ: CALIBRARE RESTAURATĂ · 🔎 ${"%.1f".format(restoredZoom)}×"
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
            status("Am identificat $recognized pagini din ${uris.size}. Aceste poze nu se afișează peste pânză; sunt doar harta internă.")
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

    /** Schimbă doar pagina-sursă folosită pentru învățarea simbolurilor. Overlay-ul rămâne global. */
    private fun activatePage(number: Int, keepGuide: Boolean) {
        pageNumber = number.coerceIn(1, BuiltInGoblenProject.PAGE_COUNT)
        val page = BuiltInGoblenProject.page(pageNumber)
        val loaded = prefs.getString("page_uri_$pageNumber", null) != null
        pageText.text = "HARTĂ P$pageNumber/16 · R${page.startRow + 1}-${page.endRowInclusive + 1} · C${page.startCol + 1}-${page.endColInclusive + 1} · ${if (loaded) "OK" else "LIPSĂ"}"

        if (!keepGuide) resetGuide()
        loadPageForLearning(pageNumber)
    }

    private fun loadPageForLearning(number: Int) {
        val generation = ++pageLoadGeneration
        val page = BuiltInGoblenProject.page(number)
        val saved = prefs.getString("page_uri_$number", null)
        if (saved == null) {
            recyclePage()
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
            val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return@withContext null
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
        symbolText.text = "Cartela $code · ${definition.name} · pe pânză apare doar SIMBOLUL"
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
                status("Cartela $code este aleasă, dar nu ai încărcat diagramele. Apasă «ÎNCARCĂ 16».")
                return@launch
            }

            val trained = readSavedFingerprint(code)
            val fingerprint = trained ?: withContext(Dispatchers.Default) { BuiltInSymbolCatalog.fingerprint(code) }
            val threshold = if (trained != null) 0.22f else 0.28f

            var processed = 0
            for (pageNo in loadedPages) {
                processed++
                status("Cartela $code: caut simbolul în harta goblenului… $processed/${loadedPages.size}")
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

            val all = allMatches()
            if (all.isEmpty()) {
                overlayView.setTargets(emptyList(), selectedSymbolBitmap)
                status(
                    if (trained != null) {
                        "Cartela $code nu a fost găsită în paginile încărcate."
                    } else {
                        "Nu recunosc sigur forma cartelei $code. Deschide o pagină unde o vezi, apasă ÎNVAȚĂ SIMBOL și atinge simbolul o singură dată."
                    }
                )
                return@launch
            }

            overlayView.setTargets(all, selectedSymbolBitmap)
            prepareGuide(all)
            applyFocus()

            status(
                if (overlayView.hasCalibration()) {
                    "Cartela $code: ${all.size} poziții. Simbolul este suprapus direct pe pânza reală."
                } else {
                    "Cartela $code: ${all.size} poziții găsite. Acum fă CALIBREAZĂ PÂNZA o singură dată ca să apară pe pânză."
                }
            )
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
            status("Alege cu ◀/▶ o pagină în care vezi simbolul și încarcă acea fotografie.")
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
            status("Cartela $code: am memorat exact simbolul tipărit. Reanalizez toate cele 16 pagini.")
            searchCodeAcrossProject(code)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun setDisplayedSymbol(code: Int) {
        selectedSymbolBitmap?.takeIf { !it.isRecycled }?.recycle()
        selectedSymbolBitmap = loadSavedSymbol(code) ?: BuiltInSymbolCatalog.render(code)
        symbolPreview.setImageBitmap(selectedSymbolBitmap)
        overlayView.setTargets(allMatches(), selectedSymbolBitmap)
    }

    private fun allMatches(): List<GridCell> = matchesByPage.values.flatten()

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
            ordered.addAll(
                if (direction > 0) rowCells.sortedBy { it.col }
                else rowCells.sortedByDescending { it.col }
            )
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
            return
        }

        val targetPage = pageForCell(cell)
        if (targetPage != pageNumber) activatePage(targetPage, keepGuide = true)

        val direction = rowDirections[cell.row] ?: 1
        overlayView.setTargets(allMatches(), selectedSymbolBitmap)
        overlayView.setFocusedCell(cell, direction)
        val arrow = if (direction > 0) "→" else "←"
        guideText.text = "${navigationIndex + 1}/${navigationTargets.size} · R${cell.row + 1} C${cell.col + 1} · $arrow"
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

    private fun saveFingerprint(code: Int, fingerprint: BooleanArray) {
        val encoded = buildString(fingerprint.size) {
            fingerprint.forEach { append(if (it) '1' else '0') }
        }
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
            FileOutputStream(symbolFile(code)).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (_: Throwable) {
            // Fingerprintul rămâne memorat chiar dacă preview-ul nu poate fi scris.
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
        textRecognizer.close()
        recyclePage()
        selectedSymbolBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}
