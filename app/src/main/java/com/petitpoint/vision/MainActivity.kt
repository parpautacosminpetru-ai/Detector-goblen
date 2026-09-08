package com.petitpoint.vision

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Size
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
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
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.petitpoint.vision.model.CodeLegendEntry
import com.petitpoint.vision.model.GridCell
import com.petitpoint.vision.model.GridRegion
import com.petitpoint.vision.model.PatternGrid
import com.petitpoint.vision.ui.PatternOverlayView
import com.petitpoint.vision.ui.PatternPickerView
import com.petitpoint.vision.ui.ScannerOverlayView
import com.petitpoint.vision.vision.AnchorTracker
import com.petitpoint.vision.vision.GrayFrame
import com.petitpoint.vision.vision.GridDetector
import com.petitpoint.vision.vision.LegendCodeExtractor
import com.petitpoint.vision.vision.ProgressDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: PatternOverlayView
    private lateinit var scannerOverlay: ScannerOverlayView
    private lateinit var statusText: TextView
    private lateinit var detectorText: TextView
    private lateinit var autoButton: Button
    private lateinit var scanButton: Button

    private lateinit var codeSearchInput: EditText
    private lateinit var codeSearchButton: Button
    private lateinit var prevTargetButton: Button
    private lateinit var nextTargetButton: Button
    private lateinit var guidePositionText: TextView

    private var camera: Camera? = null
    private var patternBitmap: Bitmap? = null
    private var patternGrid: PatternGrid? = null

    private var gridRows = 100
    private var gridCols = 100
    private var currentRegion = GridRegion(0, 0, 100, 100)
    private var selectedCell: GridCell? = null
    private var selectedCode: String? = null
    private var targetCount = 0

    private val codeLegend = LinkedHashMap<String, CodeLegendEntry>()
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val anchorTracker = AnchorTracker()
    private val progressDetector = ProgressDetector()
    private val gridDetector = GridDetector()

    private var navigationTargets: List<GridCell> = emptyList()
    private var navigationIndex = -1
    private var navigationCompleted: Set<GridCell> = emptySet()

    @Volatile
    private var autoTrackingEnabled = true

    @Volatile
    private var scannerEnabled = true

    @Volatile
    private var analysisCalibration: List<PointF> = emptyList()

    @Volatile
    private var analysisTargets: List<GridCell> = emptyList()

    @Volatile
    private var analysisRegion = GridRegion(0, 0, 100, 100)

    @Volatile
    private var trackingLostAnnounced = false

    @Volatile
    private var latestScannerCorners: List<PointF> = emptyList()

    @Volatile
    private var latestScannerConfidence = 0f

    @Volatile
    private var latestScannerColumns = 0

    @Volatile
    private var latestScannerRows = 0

    private var scannerFrameCounter = 0
    private var gestureZoomRatio = 1f

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else status("Camera este necesară pentru ghidajul live.")
    }

    private val patternPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            loadPattern(uri)
        }
    }

    private val legendPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(::persistReadPermission)
            loadLegendPhotos(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        scannerOverlay = findViewById(R.id.scannerOverlay)
        statusText = findViewById(R.id.statusText)
        detectorText = findViewById(R.id.detectorText)
        autoButton = findViewById(R.id.autoButton)
        scanButton = findViewById(R.id.scanButton)

        codeSearchInput = findViewById(R.id.codeSearchInput)
        codeSearchButton = findViewById(R.id.codeSearchButton)
        prevTargetButton = findViewById(R.id.prevTargetButton)
        nextTargetButton = findViewById(R.id.nextTargetButton)
        guidePositionText = findViewById(R.id.guidePositionText)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        findViewById<Button>(R.id.loadButton).setOnClickListener {
            patternPickerLauncher.launch(arrayOf("image/*"))
        }
        findViewById<Button>(R.id.legendButton).setOnClickListener {
            legendPickerLauncher.launch(arrayOf("image/*"))
        }
        findViewById<Button>(R.id.codeButton).setOnClickListener { showRecognizedCodes() }
        findViewById<Button>(R.id.gridButton).setOnClickListener { showGridDialog() }
        findViewById<Button>(R.id.symbolButton).setOnClickListener { showSymbolPicker() }
        findViewById<Button>(R.id.calibrateButton).setOnClickListener { beginCalibration() }
        findViewById<Button>(R.id.autoAlignButton).setOnClickListener { autoAlignFromScanner() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetOverlay() }
        autoButton.setOnClickListener { toggleAutoTracking() }
        scanButton.setOnClickListener { toggleScanner() }

        codeSearchButton.setOnClickListener { searchCodeFromBar() }
        codeSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                searchCodeFromBar()
                true
            } else {
                false
            }
        }
        prevTargetButton.setOnClickListener { moveGuidance(-1) }
        nextTargetButton.setOnClickListener { moveGuidance(1) }
        resetGuidanceUi()

        findViewById<Button>(R.id.rebaselineButton).setOnClickListener {
            progressDetector.resetBaselineKeepCompleted()
            status("Progresul rămas va fi reînvățat din mai multe cadre. Ține mâna în afara cadrului o clipă.")
        }

        findViewById<SeekBar>(R.id.opacitySeek).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    overlayView.setOverlayOpacity(progress.coerceAtLeast(20))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )

        overlayView.onCalibrationProgress = { pointCount ->
            val next = listOf("stânga-sus", "dreapta-sus", "dreapta-jos", "stânga-jos")
            if (pointCount < 4) {
                status("Calibrare: atinge colțul ${next[pointCount]} al regiunii de pânză.")
            }
        }
        overlayView.onCalibrationComplete = {
            analysisCalibration = overlayView.calibrationPointsSnapshot()
            anchorTracker.reset()
            progressDetector.resetBaselineKeepCompleted()
            trackingLostAnnounced = false
            val label = selectedCode?.let { "codul $it" } ?: "simbolul selectat"
            status("Aliniere fixată pentru $label. $targetCount poziții sunt suprapuse pe ochiuri.")
            applyGuidanceFocus(announce = false)
        }

        setupPinchZoom()
        requestCameraIfNeeded()
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Unele aplicații de fișiere nu oferă permisiune persistentă.
        }
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
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(analysisExecutor) { image ->
                        try {
                            processFrame(GrayFrame.from(image))
                        } finally {
                            image.close()
                        }
                    }
                }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            status(
                if (patternBitmap == null) {
                    "Camera live. SCAN caută grila. Încarcă diagrama și pozele cu legenda codurilor."
                } else {
                    "Diagrama este încărcată. Citește codurile din poze sau caută un cod deja recunoscut."
                }
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(frame: GrayFrame) {
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        scannerFrameCounter++
        if (scannerEnabled && scannerFrameCounter % 3 == 0) {
            processScanner(frame, viewWidth, viewHeight)
        }

        var points = analysisCalibration.map { PointF(it.x, it.y) }

        if (autoTrackingEnabled && points.size == 4) {
            val tracked = anchorTracker.process(frame, viewWidth, viewHeight, points)
            if (tracked != null) {
                if (tracked.stable) {
                    points = tracked.viewPoints
                    analysisCalibration = tracked.viewPoints.map { PointF(it.x, it.y) }
                    if (tracked.hasMoved) {
                        val uiPoints = tracked.viewPoints.map { PointF(it.x, it.y) }
                        overlayView.post { overlayView.setTrackedCalibrationPoints(uiPoints) }
                    }
                    if (trackingLostAnnounced) {
                        trackingLostAnnounced = false
                        overlayView.post { status("AUTO a regăsit pânza și a realiniat suprapunerea.") }
                    }
                } else if (!trackingLostAnnounced) {
                    trackingLostAnnounced = true
                    overlayView.post {
                        status("AUTO a pierdut temporar reperele. SCAN rămâne activ; folosește Auto-aliniază dacă e nevoie.")
                    }
                }
            }
        }

        val targets = analysisTargets
        if (points.size != 4 || targets.isEmpty()) return

        val samples = mapTargetsToFrame(
            frame = frame,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            points = points,
            region = analysisRegion,
            targets = targets
        )
        if (samples.isEmpty()) return

        val progress = progressDetector.process(frame, samples)
        if (progress.baselineJustCaptured) {
            overlayView.post {
                status("Referința live este stabilă. Când o cusătură schimbă persistent celula, ea dispare din ghidaj.")
            }
        }
        if (progress.newlyCompleted.isNotEmpty()) {
            val completed = progressDetector.completedSnapshot()
            overlayView.post {
                navigationCompleted = completed
                overlayView.setCompletedCells(completed)
                val current = currentGuidanceCell()
                if (current != null && completed.contains(current)) {
                    if (!moveGuidance(1, automatic = true)) {
                        applyGuidanceFocus(announce = false)
                    }
                } else {
                    applyGuidanceFocus(announce = false)
                }
                val codeLabel = selectedCode?.let { " pentru codul $it" } ?: ""
                status("Detectate ${completed.size} executate din $targetCount$codeLabel.")
            }
        }
    }

    private fun processScanner(frame: GrayFrame, viewWidth: Int, viewHeight: Int) {
        val detection = gridDetector.detect(frame)
        latestScannerConfidence = detection.confidence

        val verticalView = detection.verticalLines
            .map { x -> frame.frameToView(PointF(x, frame.height / 2f), viewWidth, viewHeight).x }
            .filter { x -> x >= 0f && x <= viewWidth.toFloat() }
            .sorted()

        val horizontalView = detection.horizontalLines
            .map { y -> frame.frameToView(PointF(frame.width / 2f, y), viewWidth, viewHeight).y }
            .filter { y -> y >= 0f && y <= viewHeight.toFloat() }
            .sorted()

        latestScannerColumns = (verticalView.size - 1).coerceAtLeast(0)
        latestScannerRows = (horizontalView.size - 1).coerceAtLeast(0)

        val usableV = verticalView.filter { it > viewWidth * 0.03f && it < viewWidth * 0.97f }
        val usableH = horizontalView.filter { it > viewHeight * 0.06f && it < viewHeight * 0.94f }
        latestScannerCorners = if (usableV.size >= 3 && usableH.size >= 3) {
            listOf(
                PointF(usableV.first(), usableH.first()),
                PointF(usableV.last(), usableH.first()),
                PointF(usableV.last(), usableH.last()),
                PointF(usableV.first(), usableH.last())
            )
        } else {
            emptyList()
        }

        scannerOverlay.post {
            scannerOverlay.setDetectedGrid(verticalView, horizontalView, detection.confidence)
            detectorText.text = when {
                detection.confidence >= 0.45f -> {
                    "SCAN: GRILĂ clară ${(detection.confidence * 100).toInt()}% · pas ${"%.1f".format(detection.cellWidthPx)}×${"%.1f".format(detection.cellHeightPx)} px · ≈$latestScannerColumns×$latestScannerRows celule"
                }
                detection.confidence >= 0.18f -> {
                    "SCAN: grilă posibilă ${(detection.confidence * 100).toInt()}% · apropie/zoom pentru precizie"
                }
                else -> {
                    "SCAN: caut grila… ține telefonul drept, focalizează pânza și mărește până se văd ochiurile"
                }
            }
        }
    }

    private fun autoAlignFromScanner() {
        if (patternGrid == null || selectedCell == null) {
            status("Încarcă diagrama și alege un cod recunoscut sau un simbol manual înainte de Auto-aliniază.")
            return
        }
        val corners = latestScannerCorners.map { PointF(it.x, it.y) }
        if (latestScannerConfidence < 0.18f || corners.size != 4) {
            status("SCAN nu are încă o grilă suficient de stabilă. Apropie camera și fă zoom până apar liniile verzi.")
            return
        }

        overlayView.setTrackedCalibrationPoints(corners)
        analysisCalibration = corners
        anchorTracker.reset()
        progressDetector.resetBaselineKeepCompleted()
        trackingLostAnnounced = false
        val codeLabel = selectedCode?.let { " pentru codul $it" } ?: ""
        status("Auto-aliniat$codeLabel pe grila detectată (≈$latestScannerColumns×$latestScannerRows celule vizibile).")
        applyGuidanceFocus(announce = false)
    }

    private fun mapTargetsToFrame(
        frame: GrayFrame,
        viewWidth: Int,
        viewHeight: Int,
        points: List<PointF>,
        region: GridRegion,
        targets: List<GridCell>
    ): List<ProgressDetector.CellSample> {
        if (points.size != 4) return emptyList()

        val src = floatArrayOf(
            region.startCol.toFloat(), region.startRow.toFloat(),
            (region.startCol + region.colCount).toFloat(), region.startRow.toFloat(),
            (region.startCol + region.colCount).toFloat(), (region.startRow + region.rowCount).toFloat(),
            region.startCol.toFloat(), (region.startRow + region.rowCount).toFloat()
        )
        val dst = floatArrayOf(
            points[0].x, points[0].y,
            points[1].x, points[1].y,
            points[2].x, points[2].y,
            points[3].x, points[3].y
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return emptyList()

        fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
        val topWidth = distance(points[0], points[1])
        val bottomWidth = distance(points[3], points[2])
        val leftHeight = distance(points[0], points[3])
        val rightHeight = distance(points[1], points[2])
        val cellWidthView = ((topWidth + bottomWidth) * 0.5f) / region.colCount.coerceAtLeast(1)
        val cellHeightView = ((leftHeight + rightHeight) * 0.5f) / region.rowCount.coerceAtLeast(1)
        val viewScale = max(
            viewWidth.toFloat() / frame.width.toFloat(),
            viewHeight.toFloat() / frame.height.toFloat()
        )
        val sampleRadius = ((min(cellWidthView, cellHeightView) / viewScale) * 0.38f)
            .toInt()
            .coerceIn(3, 14)

        val result = ArrayList<ProgressDetector.CellSample>()
        for (cell in targets) {
            if (!region.contains(cell)) continue
            val xy = floatArrayOf(cell.col + 0.5f, cell.row + 0.5f)
            matrix.mapPoints(xy)
            val framePoint = frame.viewToFrame(PointF(xy[0], xy[1]), viewWidth, viewHeight) ?: continue
            result.add(
                ProgressDetector.CellSample(
                    cell = cell,
                    frameX = framePoint.x,
                    frameY = framePoint.y,
                    radius = sampleRadius
                )
            )
        }
        return result
    }

    private fun setupPinchZoom() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    val state = camera?.cameraInfo?.zoomState?.value ?: return false
                    gestureZoomRatio = state.zoomRatio
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val activeCamera = camera ?: return false
                    val state = activeCamera.cameraInfo.zoomState.value ?: return false
                    val previous = gestureZoomRatio
                    val requested = (previous * detector.scaleFactor)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    if (requested == previous) return true

                    val visualFactor = requested / previous
                    gestureZoomRatio = requested
                    activeCamera.cameraControl.setZoomRatio(requested)

                    if (overlayView.hasCalibration()) {
                        overlayView.scaleCalibrationAbout(
                            previewView.width / 2f,
                            previewView.height / 2f,
                            visualFactor
                        )
                        analysisCalibration = overlayView.calibrationPointsSnapshot()
                        anchorTracker.reset()
                        progressDetector.resetBaselineKeepCompleted()
                    }
                    return true
                }
            }
        )

        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            true
        }
    }

    private fun loadPattern(uri: Uri) {
        status("Încarc diagrama offline…")
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
            if (bitmap == null) {
                status("Nu am putut citi imaginea diagramei.")
                return@launch
            }

            patternBitmap?.takeIf { !it.isRecycled }?.recycle()
            patternBitmap = bitmap
            selectedCell = null
            selectedCode = null
            targetCount = 0
            analysisTargets = emptyList()
            analysisCalibration = emptyList()
            anchorTracker.reset()
            progressDetector.resetAll()
            overlayView.setCompletedCells(emptySet())
            resetGuidance()
            rebuildPatternGrid()
            status("Diagrama este încărcată. Configurează grila, apoi apasă Coduri din poze sau caută codul.")
            showGridDialog()
        }
    }

    private fun loadLegendPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return

        overlayView.setTargets(emptyList(), null)
        overlayView.setCompletedCells(emptySet())
        selectedCell = null
        selectedCode = null
        analysisTargets = emptyList()
        analysisCalibration = emptyList()
        progressDetector.resetAll()
        resetGuidance()
        clearLegendEntries()
        status("Citesc codurile și simbolurile din ${uris.size} poz${if (uris.size == 1) "ă" else "e"}…")

        lifecycleScope.launch {
            var readablePhotos = 0
            for ((index, uri) in uris.withIndex()) {
                status("OCR offline: poza ${index + 1}/${uris.size}…")
                val bitmap = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                } ?: continue

                try {
                    val text = recognizeText(bitmap)
                    val entries = withContext(Dispatchers.Default) {
                        LegendCodeExtractor.extract(bitmap, text)
                    }
                    if (entries.isNotEmpty()) readablePhotos++
                    for (entry in entries) {
                        val previous = codeLegend[entry.code]
                        if (previous == null || entry.score > previous.score) {
                            previous?.symbolBitmap?.takeIf { !it.isRecycled }?.recycle()
                            codeLegend[entry.code] = entry
                        } else {
                            entry.symbolBitmap.takeIf { !it.isRecycled }?.recycle()
                        }
                    }
                } catch (_: Throwable) {
                    // Continuăm cu celelalte poze.
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }

            if (codeLegend.isEmpty()) {
                status("Nu am putut asocia coduri cu simboluri. Fotografiază legenda clar, drept, cu simbolul și codul pe același rând.")
            } else {
                codeSearchInput.hint = "Caută cod (${codeLegend.size} recunoscute)"
                status("Am recunoscut ${codeLegend.size} coduri cu simbol din $readablePhotos poz${if (readablePhotos == 1) "ă" else "e"}. Scrie codul în bara de căutare.")
                showRecognizedCodes()
            }
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): Text = suspendCancellableCoroutine { continuation ->
        val task = textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
        task.addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        task.addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun sortedLegendEntries(): List<CodeLegendEntry> = codeLegend.values.sortedWith(
        compareBy<CodeLegendEntry>(
            { it.code.toIntOrNull() == null },
            { it.code.toIntOrNull() ?: Int.MAX_VALUE },
            { it.code }
        )
    )

    private fun showRecognizedCodes(entries: List<CodeLegendEntry> = sortedLegendEntries()) {
        if (codeLegend.isEmpty()) {
            status("Nu am coduri citite încă. Apasă Coduri din poze și selectează fotografiile cu legenda.")
            return
        }
        if (entries.isEmpty()) {
            status("Niciun cod nu corespunde căutării.")
            return
        }

        val labels = entries.map { entry -> "Cod ${entry.code}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Coduri recunoscute (${entries.size})")
            .setMessage("Alege codul. Traseul va fi stânga → dreapta pe un rând și dreapta → stânga pe următorul.")
            .setItems(labels) { _, which -> analyzeRecognizedCode(entries[which]) }
            .setNegativeButton("Închide", null)
            .show()
    }

    private fun searchCodeFromBar() {
        if (codeLegend.isEmpty()) {
            status("Întâi apasă Coduri din poze ca să citesc codurile și simbolurile din legendă.")
            return
        }

        val query = codeSearchInput.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            showRecognizedCodes()
            return
        }

        val exact = codeLegend.values.firstOrNull { it.code.equals(query, ignoreCase = true) }
        if (exact != null) {
            analyzeRecognizedCode(exact)
            return
        }

        val partial = sortedLegendEntries().filter { it.code.contains(query, ignoreCase = true) }
        when (partial.size) {
            0 -> status("Codul «$query» nu apare între codurile recunoscute din poze.")
            1 -> analyzeRecognizedCode(partial.first())
            else -> showRecognizedCodes(partial)
        }
    }

    private fun analyzeRecognizedCode(entry: CodeLegendEntry) {
        val grid = patternGrid
        if (grid == null) {
            selectedCode = entry.code
            codeSearchInput.setText(entry.code)
            status("Codul ${entry.code} a fost citit. Încarcă fotografia diagramei ca să găsesc simbolul în toate căsuțele.")
            return
        }

        selectedCode = entry.code
        selectedCell = null
        codeSearchInput.setText(entry.code)
        status("Cod ${entry.code}: caut simbolul recunoscut în toată diagrama…")
        lifecycleScope.launch {
            val matches = withContext(Dispatchers.Default) {
                grid.matchingFingerprint(entry.fingerprint, maxDistance = 0.30f)
            }

            if (matches.isEmpty()) {
                analysisTargets = emptyList()
                targetCount = 0
                overlayView.setTargets(emptyList(), null)
                resetGuidance()
                status("Am citit codul ${entry.code}, dar simbolul extras din legendă nu se potrivește suficient cu diagrama. Refa poza legendei sau folosește Simbol manual.")
                return@launch
            }

            selectedCell = matches.first()
            targetCount = matches.size
            analysisTargets = matches
            analysisCalibration = emptyList()
            anchorTracker.reset()
            progressDetector.resetAll()
            navigationCompleted = emptySet()
            overlayView.setCompletedCells(emptySet())
            overlayView.setTargets(matches, entry.symbolBitmap)
            prepareGuidance(matches)
            val visible = navigationTargets.size
            status("Cod ${entry.code} → $targetCount poziții în diagramă, $visible în regiunea curentă. Urmează ◀/▶ în zig-zag și apoi Auto-aliniază.")
        }
    }

    private fun prepareGuidance(cells: List<GridCell>) {
        val byRow = cells
            .filter { currentRegion.contains(it) }
            .groupBy { it.row }
            .toSortedMap()

        val ordered = ArrayList<GridCell>()
        for ((row, rowCells) in byRow) {
            val leftToRight = ((row - currentRegion.startRow) and 1) == 0
            val sorted = if (leftToRight) {
                rowCells.sortedBy { it.col }
            } else {
                rowCells.sortedByDescending { it.col }
            }
            ordered.addAll(sorted)
        }

        navigationTargets = ordered
        navigationCompleted = progressDetector.completedSnapshot()
        navigationIndex = navigationTargets.indexOfFirst { !navigationCompleted.contains(it) }
        if (navigationIndex < 0 && navigationTargets.isNotEmpty()) navigationIndex = 0
        applyGuidanceFocus(announce = false)
    }

    private fun currentGuidanceCell(): GridCell? =
        navigationTargets.getOrNull(navigationIndex)

    private fun directionFor(cell: GridCell): Int =
        if (((cell.row - currentRegion.startRow) and 1) == 0) 1 else -1

    private fun moveGuidance(step: Int, automatic: Boolean = false): Boolean {
        if (navigationTargets.isEmpty() || navigationIndex !in navigationTargets.indices) {
            if (!automatic) status("Alege mai întâi un cod care are poziții în regiunea curentă.")
            return false
        }

        var candidate = navigationIndex + step
        while (candidate in navigationTargets.indices && navigationCompleted.contains(navigationTargets[candidate])) {
            candidate += step
        }

        if (candidate !in navigationTargets.indices) {
            if (!automatic) {
                status(if (step > 0) "Ai ajuns la ultima poziție a codului în traseul curent." else "Ești la prima poziție a codului în traseul curent.")
            }
            return false
        }

        navigationIndex = candidate
        applyGuidanceFocus(announce = !automatic)
        return true
    }

    private fun applyGuidanceFocus(announce: Boolean) {
        val cell = currentGuidanceCell()
        if (cell == null) {
            overlayView.setFocusedCell(null, 1)
            resetGuidanceUi()
            return
        }

        val direction = directionFor(cell)
        overlayView.setFocusedCell(cell, direction)
        val codeLabel = selectedCode ?: "SIM"
        val arrow = if (direction > 0) "→" else "←"
        val directionText = if (direction > 0) "stânga → dreapta" else "dreapta → stânga"
        guidePositionText.text = "$codeLabel  ${navigationIndex + 1}/${navigationTargets.size}  R${cell.row + 1} C${cell.col + 1}  $arrow"
        prevTargetButton.isEnabled = navigationIndex > 0
        nextTargetButton.isEnabled = navigationIndex < navigationTargets.lastIndex

        if (announce) {
            status("Cod $codeLabel: poziția ${navigationIndex + 1}/${navigationTargets.size}, rând ${cell.row + 1}, coloană ${cell.col + 1}. Direcție $directionText.")
        }
    }

    private fun resetGuidance() {
        navigationTargets = emptyList()
        navigationCompleted = emptySet()
        navigationIndex = -1
        overlayView.setFocusedCell(null, 1)
        resetGuidanceUi()
    }

    private fun resetGuidanceUi() {
        if (::guidePositionText.isInitialized) guidePositionText.text = "Niciun cod ales"
        if (::prevTargetButton.isInitialized) prevTargetButton.isEnabled = false
        if (::nextTargetButton.isInitialized) nextTargetButton.isEnabled = false
    }

    private fun clearLegendEntries() {
        for (entry in codeLegend.values) {
            entry.symbolBitmap.takeIf { !it.isRecycled }?.recycle()
        }
        codeLegend.clear()
    }

    private fun rebuildPatternGrid() {
        val bitmap = patternBitmap ?: return
        patternGrid = PatternGrid(bitmap, gridRows, gridCols)

        val safeStartRow = currentRegion.startRow.coerceIn(0, gridRows - 1)
        val safeStartCol = currentRegion.startCol.coerceIn(0, gridCols - 1)
        currentRegion = GridRegion(
            safeStartRow,
            safeStartCol,
            currentRegion.rowCount.coerceIn(1, gridRows - safeStartRow),
            currentRegion.colCount.coerceIn(1, gridCols - safeStartCol)
        )
        analysisRegion = currentRegion
        analysisCalibration = emptyList()
        anchorTracker.reset()
        progressDetector.resetAll()
        resetGuidance()
        overlayView.configureGrid(gridRows, gridCols, currentRegion)
        overlayView.setTargets(emptyList(), null)
        overlayView.setCompletedCells(emptySet())
    }

    private fun showGridDialog() {
        if (patternBitmap == null) {
            status("Încarcă mai întâi imaginea diagramei.")
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(18)
            setPadding(p, dp(6), p, dp(4))
        }

        fun numberField(label: String, value: Int): EditText = EditText(this).apply {
            hint = label
            setText(value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            selectAll()
            container.addView(
                this,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val rowsField = numberField("Rânduri totale", gridRows)
        val colsField = numberField("Coloane totale", gridCols)
        val startRowField = numberField("Regiune: rând de start (1 = primul)", currentRegion.startRow + 1)
        val startColField = numberField("Regiune: coloană de start (1 = prima)", currentRegion.startCol + 1)
        val visibleRowsField = numberField("Câte rânduri vede camera", currentRegion.rowCount)
        val visibleColsField = numberField("Câte coloane vede camera", currentRegion.colCount)

        AlertDialog.Builder(this)
            .setTitle("Grila Petit Point")
            .setMessage("Regiunea este porțiunea de diagramă pe care o vezi acum prin cameră. SCAN estimează separat câte celule vede.")
            .setView(container)
            .setNegativeButton("Anulează", null)
            .setPositiveButton("Aplică") { _, _ ->
                gridRows = parsePositive(rowsField, gridRows).coerceAtMost(2000)
                gridCols = parsePositive(colsField, gridCols).coerceAtMost(2000)

                val startRow = (parsePositive(startRowField, 1) - 1).coerceIn(0, gridRows - 1)
                val startCol = (parsePositive(startColField, 1) - 1).coerceIn(0, gridCols - 1)
                val visibleRows = parsePositive(visibleRowsField, gridRows - startRow)
                    .coerceIn(1, gridRows - startRow)
                val visibleCols = parsePositive(visibleColsField, gridCols - startCol)
                    .coerceIn(1, gridCols - startCol)

                currentRegion = GridRegion(startRow, startCol, visibleRows, visibleCols)
                analysisRegion = currentRegion
                rebuildPatternGrid()
                selectedCell = null
                selectedCode = null
                targetCount = 0
                analysisTargets = emptyList()
                status("Grilă: ${gridRows}×${gridCols}. Regiune cameră: ${visibleRows}×${visibleCols}. Caută codul sau alege simbolul.")
            }
            .show()
    }

    private fun showSymbolPicker() {
        val grid = patternGrid
        if (grid == null) {
            status("Încarcă diagrama și configurează grila mai întâi.")
            return
        }

        val picker = PatternPickerView(this).apply { setGrid(grid) }
        val dialog = Dialog(this).apply {
            setContentView(picker)
            window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }

        picker.onCellSelected = { cell ->
            dialog.dismiss()
            analyzeSelectedSymbol(grid, cell)
        }

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun analyzeSelectedSymbol(grid: PatternGrid, cell: GridCell) {
        selectedCell = cell
        selectedCode = null
        status("Compar simbolul ales cu toate celulele diagramei…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val matches = grid.matchingCells(cell, maxDistance = 0.24f)
                val symbol = grid.symbolBitmap(cell)
                matches to symbol
            }
            targetCount = result.first.size
            analysisTargets = result.first
            analysisCalibration = emptyList()
            anchorTracker.reset()
            progressDetector.resetAll()
            navigationCompleted = emptySet()
            overlayView.setCompletedCells(emptySet())
            overlayView.setTargets(result.first, result.second)
            prepareGuidance(result.first)
            status("Simbol manual găsit în $targetCount căsuțe. Traseul zig-zag este pregătit; Auto-aliniază sau Calibrează.")
        }
    }

    private fun beginCalibration() {
        if (patternGrid == null) {
            status("Încarcă diagrama înainte de calibrare.")
            return
        }
        if (selectedCell == null) {
            status("Alege mai întâi un cod recunoscut sau un simbol manual.")
            return
        }
        analysisCalibration = emptyList()
        anchorTracker.reset()
        progressDetector.resetBaselineKeepCompleted()
        overlayView.beginCalibration()
    }

    private fun toggleScanner() {
        scannerEnabled = !scannerEnabled
        scanButton.text = if (scannerEnabled) "SCAN: ON" else "SCAN: OFF"
        if (!scannerEnabled) {
            scannerOverlay.clearDetectedGrid()
            detectorText.text = "SCAN: oprit"
            latestScannerCorners = emptyList()
            latestScannerConfidence = 0f
        } else {
            detectorText.text = "SCAN: caut grila Petit Point…"
        }
    }

    private fun toggleAutoTracking() {
        autoTrackingEnabled = !autoTrackingEnabled
        autoButton.text = if (autoTrackingEnabled) "AUTO: ON" else "AUTO: OFF"
        anchorTracker.reset()
        if (autoTrackingEnabled && overlayView.hasCalibration()) {
            analysisCalibration = overlayView.calibrationPointsSnapshot()
            status("Urmărirea automată este pornită. SCAN-ul verde rămâne un detector independent.")
        } else {
            status("Urmărirea automată este oprită. Suprapunerea rămâne pe ultima calibrare.")
        }
    }

    private fun resetOverlay() {
        overlayView.clearCalibration()
        overlayView.setTargets(emptyList(), null)
        overlayView.setCompletedCells(emptySet())
        overlayView.setFocusedCell(null, 1)
        selectedCell = null
        selectedCode = null
        targetCount = 0
        analysisTargets = emptyList()
        analysisCalibration = emptyList()
        anchorTracker.reset()
        progressDetector.resetAll()
        resetGuidance()
        status("Suprapunerea a fost resetată. Codurile citite rămân disponibile în bara de căutare.")
    }

    private fun parsePositive(field: EditText, fallback: Int): Int =
        field.text?.toString()?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

    private fun status(message: String) {
        statusText.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdownNow()
        textRecognizer.close()
        clearLegendEntries()
    }
}
