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
import com.petitpoint.vision.model.BuiltInGoblenProject
import com.petitpoint.vision.model.GridCell
import com.petitpoint.vision.model.PatternGrid
import com.petitpoint.vision.ui.PatternOverlayView
import com.petitpoint.vision.ui.PatternPickerView
import com.petitpoint.vision.vision.BuiltInSymbolCatalog
import com.petitpoint.vision.vision.ChartGridCropper
import com.petitpoint.vision.vision.SymbolFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Mod simplificat, construit pentru goblenul concret din conversație:
 * 200 x 250 ochiuri, 16 pagini, 59 de cartele.
 *
 * Utilizatorul caută NUMĂRUL cartelei, dar peste pânză desenăm numai SIMBOLUL.
 */
class GoblenActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: PatternOverlayView
    private lateinit var statusText: TextView
    private lateinit var pageText: TextView
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
    private var selectedCode: Int? = null
    private var selectedSymbolBitmap: Bitmap? = null

    private var navigationTargets: List<GridCell> = emptyList()
    private var navigationIndex = -1
    private var rowDirections: Map<Int, Int> = emptyMap()

    private val prefs by lazy { getSharedPreferences("custom_goblen_project", MODE_PRIVATE) }

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
            loadCurrentPage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_goblen)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        pageText = findViewById(R.id.pageText)
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
        findViewById<Button>(R.id.searchCodeButton).setOnClickListener { searchCode() }
        findViewById<Button>(R.id.learnSymbolButton).setOnClickListener { learnCurrentSymbol() }
        findViewById<Button>(R.id.calibrateButton).setOnClickListener { beginCalibration() }
        findViewById<Button>(R.id.clearCalibrationButton).setOnClickListener {
            overlayView.clearCalibration()
            status("Calibrarea paginii $pageNumber a fost ștearsă.")
        }
        prevTargetButton.setOnClickListener { moveTarget(-1) }
        nextTargetButton.setOnClickListener { moveTarget(1) }

        codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                searchCode(); true
            } else false
        }

        overlayView.onCalibrationProgress = { count ->
            val names = listOf("stânga-sus", "dreapta-sus", "dreapta-jos", "stânga-jos")
            if (count < 4) status("Pagina $pageNumber: atinge colțul ${names[count]} al zonei corespunzătoare pe pânză.")
        }
        overlayView.onCalibrationComplete = {
            status("Pagina $pageNumber este aliniată. Pe pânză vezi simbolul cartelei, nu cifra.")
            applyFocus()
        }

        setupPinchZoom()
        requestCameraIfNeeded()
        loadCurrentPage()
        resetGuide()
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
            status("Goblenul tău: alege pagina, caută cartela 1–59 și calibrează pânza.")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupPinchZoom() {
        val detector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(scale: ScaleGestureDetector): Boolean {
                    val active = camera ?: return false
                    val state = active.cameraInfo.zoomState.value ?: return false
                    val next = (state.zoomRatio * scale.scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    active.cameraControl.setZoomRatio(next)
                    return true
                }
            }
        )
        previewView.setOnTouchListener { _, event -> detector.onTouchEvent(event); true }
    }

    private fun changePage(delta: Int) {
        pageNumber = (pageNumber + delta).coerceIn(1, BuiltInGoblenProject.PAGE_COUNT)
        loadCurrentPage()
    }

    private fun importCurrentPage() {
        pendingImportPage = pageNumber
        pagePickerLauncher.launch(arrayOf("image/*"))
    }

    private fun loadCurrentPage() {
        val page = BuiltInGoblenProject.page(pageNumber)
        pageText.text = "Pagina ${page.number}/16 · R${page.startRow + 1}-${page.endRowInclusive + 1} · C${page.startCol + 1}-${page.endColInclusive + 1}"
        overlayView.configureGrid(BuiltInGoblenProject.TOTAL_ROWS, BuiltInGoblenProject.TOTAL_COLS, page.region)
        overlayView.setTargets(emptyList(), selectedSymbolBitmap)
        resetGuide()

        val saved = prefs.getString("page_uri_$pageNumber", null)
        if (saved == null) {
            recyclePage()
            status("Pagina $pageNumber nu este încă încărcată în telefon. Apasă «Încarcă pagina» o singură dată.")
            return
        }

        lifecycleScope.launch {
            status("Pagina $pageNumber: găsesc automat marginile grilei tipărite…")
            val loaded = withContext(Dispatchers.IO) {
                val uri = Uri.parse(saved)
                val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
                try {
                    ChartGridCropper.crop(source, page.rowCount, page.colCount)
                } finally {
                    if (!source.isRecycled) source.recycle()
                }
            }

            if (loaded == null) {
                recyclePage()
                status("Nu am putut citi fotografia paginii $pageNumber. Reîncarc-o.")
                return@launch
            }

            recyclePage()
            pageBitmap = loaded.bitmap
            pageGrid = PatternGrid(loaded.bitmap, page.rowCount, page.colCount)
            val confidence = (loaded.confidence * 100f).toInt()
            status("Pagina $pageNumber: grila a fost decupată automat ($confidence%). Acum caută o cartelă.")
            selectedCode?.let { analyzeCodeOnCurrentPage(it) }
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
        symbolText.text = "Cartela $code · ${definition.name}"
        analyzeCodeOnCurrentPage(code)
    }

    private fun analyzeCodeOnCurrentPage(code: Int) {
        val grid = pageGrid
        val page = BuiltInGoblenProject.page(pageNumber)
        if (grid == null) {
            status("Cartela $code este aleasă. Încarcă fotografia paginii $pageNumber ca să găsesc pozițiile simbolului.")
            return
        }

        lifecycleScope.launch {
            status("Cartela $code: caut forma simbolului în pagina $pageNumber…")
            val trained = readSavedFingerprint(code)
            val fingerprint = trained ?: withContext(Dispatchers.Default) { BuiltInSymbolCatalog.fingerprint(code) }
            val localMatches = withContext(Dispatchers.Default) {
                grid.matchingFingerprint(fingerprint, maxDistance = if (trained != null) 0.23f else 0.34f)
            }
            val globalMatches = localMatches.map { GridCell(page.startRow + it.row, page.startCol + it.col) }
            overlayView.setTargets(globalMatches, selectedSymbolBitmap)
            prepareGuide(globalMatches)

            if (globalMatches.isEmpty()) {
                status(
                    if (trained == null) {
                        "Cartela $code: nu am găsit sigur simbolul pe pagina $pageNumber. Dacă știi că există aici, apasă «Învață simbolul» și atinge o singură apariție; de atunci îl țin minte."
                    } else {
                        "Cartela $code nu apare pe pagina $pageNumber. Treci la pagina următoare."
                    }
                )
            } else {
                val source = if (trained != null) "simbol exact învățat" else "simbol implicit"
                status("Cartela $code · $source · ${globalMatches.size} poziții pe pagina $pageNumber. Calibrează și coase de jos în sus.")
            }
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
            status("Încarcă mai întâi fotografia paginii curente.")
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
            status("Cartela $code: am memorat exact simbolul tipărit. Îl folosesc de acum pe toate cele 16 pagini.")
            analyzeCodeOnCurrentPage(code)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun setDisplayedSymbol(code: Int) {
        selectedSymbolBitmap?.takeIf { !it.isRecycled }?.recycle()
        selectedSymbolBitmap = loadSavedSymbol(code) ?: BuiltInSymbolCatalog.render(code)
        symbolPreview.setImageBitmap(selectedSymbolBitmap)
        overlayView.setTargets(emptyList(), selectedSymbolBitmap)
    }

    private fun prepareGuide(cells: List<GridCell>) {
        if (cells.isEmpty()) {
            resetGuide(); return
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
        applyFocus()
    }

    private fun moveTarget(delta: Int) {
        if (navigationTargets.isEmpty()) return
        navigationIndex = (navigationIndex + delta).coerceIn(0, navigationTargets.lastIndex)
        applyFocus()
    }

    private fun applyFocus() {
        val cell = navigationTargets.getOrNull(navigationIndex)
        if (cell == null) {
            overlayView.setFocusedCell(null, 1)
            resetGuide(); return
        }
        val direction = rowDirections[cell.row] ?: 1
        overlayView.setFocusedCell(cell, direction)
        val arrow = if (direction > 0) "→" else "←"
        guideText.text = "${navigationIndex + 1}/${navigationTargets.size} · R${cell.row + 1} C${cell.col + 1} · $arrow · de jos în sus"
        prevTargetButton.isEnabled = navigationIndex > 0
        nextTargetButton.isEnabled = navigationIndex < navigationTargets.lastIndex
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

    private fun beginCalibration() {
        if (navigationTargets.isEmpty()) {
            status("Mai întâi caută o cartelă care apare pe pagina curentă.")
            return
        }
        overlayView.beginCalibration()
    }

    private fun saveFingerprint(code: Int, fingerprint: BooleanArray) {
        val encoded = buildString(fingerprint.size) { fingerprint.forEach { append(if (it) '1' else '0') } }
        prefs.edit().putString("fingerprint_$code", encoded).apply()
    }

    private fun readSavedFingerprint(code: Int): BooleanArray? {
        val encoded = prefs.getString("fingerprint_$code", null) ?: return null
        if (encoded.isEmpty()) return null
        return BooleanArray(encoded.length) { encoded[it] == '1' }
    }

    private fun symbolFile(code: Int): File = File(filesDir, "goblen_symbol_$code.png")

    private fun saveSymbolBitmap(code: Int, bitmap: Bitmap) {
        try {
            FileOutputStream(symbolFile(code)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Throwable) {
            // Fingerprintul rămâne memorat chiar dacă preview-ul exact nu poate fi scris.
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
            // Unele galerii nu oferă permisiune persistentă; fotografia rămâne utilizabilă în sesiunea curentă.
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
        recyclePage()
        selectedSymbolBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}
