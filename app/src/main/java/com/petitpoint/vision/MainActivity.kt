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
import com.petitpoint.vision.model.GridCell
import com.petitpoint.vision.model.GridRegion
import com.petitpoint.vision.model.PatternGrid
import com.petitpoint.vision.ui.PatternOverlayView
import com.petitpoint.vision.ui.PatternPickerView
import com.petitpoint.vision.vision.AnchorTracker
import com.petitpoint.vision.vision.GrayFrame
import com.petitpoint.vision.vision.ProgressDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: PatternOverlayView
    private lateinit var statusText: TextView
    private lateinit var autoButton: Button

    private var camera: Camera? = null
    private var patternBitmap: Bitmap? = null
    private var patternGrid: PatternGrid? = null

    private var gridRows = 100
    private var gridCols = 100
    private var currentRegion = GridRegion(0, 0, 100, 100)
    private var selectedCell: GridCell? = null
    private var targetCount = 0

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val anchorTracker = AnchorTracker()
    private val progressDetector = ProgressDetector()

    @Volatile
    private var autoTrackingEnabled = true

    @Volatile
    private var analysisCalibration: List<PointF> = emptyList()

    @Volatile
    private var analysisTargets: List<GridCell> = emptyList()

    @Volatile
    private var analysisRegion = GridRegion(0, 0, 100, 100)

    @Volatile
    private var trackingLostAnnounced = false

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
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Unele aplicații de fișiere nu oferă permisiune persistentă; citirea curentă funcționează.
            }
            loadPattern(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        autoButton = findViewById(R.id.autoButton)

        findViewById<Button>(R.id.loadButton).setOnClickListener {
            patternPickerLauncher.launch(arrayOf("image/*"))
        }
        findViewById<Button>(R.id.gridButton).setOnClickListener { showGridDialog() }
        findViewById<Button>(R.id.symbolButton).setOnClickListener { showSymbolPicker() }
        findViewById<Button>(R.id.calibrateButton).setOnClickListener { beginCalibration() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetOverlay() }
        autoButton.setOnClickListener { toggleAutoTracking() }
        findViewById<Button>(R.id.rebaselineButton).setOnClickListener {
            progressDetector.resetBaselineKeepCompleted()
            status("Progresul rămas va fi reînvățat din imaginea live. Ține mâna în afara cadrului o clipă.")
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
            status("Aliniere fixată. AUTO urmărește pânza, iar progresul se învață local. $targetCount poziții urmărite.")
        }

        setupPinchZoom()
        requestCameraIfNeeded()
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
                .setTargetResolution(Size(640, 480))
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
                    "Camera live. Încarcă diagrama Petit Point."
                } else {
                    "Camera live. Alege simbolul și calibrează pânza."
                }
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(frame: GrayFrame) {
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        if (viewWidth <= 0 || viewHeight <= 0) return

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
                        status("AUTO a pierdut temporar reperele. Ține pânza în cadru; dacă nu revine, apasă Calibrează.")
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
                status("Progres învățat. Pe măsură ce coși, pozițiile stabile detectate ca executate dispar din ghidaj.")
            }
        }
        if (progress.newlyCompleted.isNotEmpty()) {
            val completed = progressDetector.completedSnapshot()
            overlayView.post {
                overlayView.setCompletedCells(completed)
                status("Detectate ${completed.size} executate din $targetCount pentru simbolul selectat.")
            }
        }
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

        val result = ArrayList<ProgressDetector.CellSample>()
        for (cell in targets) {
            if (!region.contains(cell)) continue
            val xy = floatArrayOf(cell.col + 0.5f, cell.row + 0.5f)
            matrix.mapPoints(xy)
            val framePoint = frame.viewToFrame(PointF(xy[0], xy[1]), viewWidth, viewHeight) ?: continue
            result.add(ProgressDetector.CellSample(cell, framePoint.x, framePoint.y))
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
                        status("Zoom ${"%.1f".format(requested)}×. Suprapunerea a fost scalată; AUTO o rafinează din camera live.")
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
            targetCount = 0
            analysisTargets = emptyList()
            analysisCalibration = emptyList()
            anchorTracker.reset()
            progressDetector.resetAll()
            overlayView.setCompletedCells(emptySet())
            rebuildPatternGrid()
            status("Diagrama este încărcată. Configurează rândurile/coloanele, apoi alege un simbol.")
            showGridDialog()
        }
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
            .setMessage("Regiunea este porțiunea de diagramă pe care o vezi acum prin cameră.")
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
                targetCount = 0
                analysisTargets = emptyList()
                status("Grilă: ${gridRows}×${gridCols}. Regiune cameră: ${visibleRows}×${visibleCols}. Alege simbolul.")
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
        status("Compar simbolul ales cu toate celulele diagramei…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val matches = grid.matchingCells(cell)
                val symbol = grid.symbolBitmap(cell)
                matches to symbol
            }
            targetCount = result.first.size
            analysisTargets = result.first
            analysisCalibration = emptyList()
            anchorTracker.reset()
            progressDetector.resetAll()
            overlayView.setCompletedCells(emptySet())
            overlayView.setTargets(result.first, result.second)
            status("Simbol găsit în $targetCount căsuțe. Acum apasă Calibrează.")
        }
    }

    private fun beginCalibration() {
        if (patternGrid == null) {
            status("Încarcă diagrama înainte de calibrare.")
            return
        }
        if (selectedCell == null) {
            status("Alege mai întâi simbolul/codul pe care vrei să-l urmărești.")
            return
        }
        analysisCalibration = emptyList()
        anchorTracker.reset()
        progressDetector.resetBaselineKeepCompleted()
        overlayView.beginCalibration()
    }

    private fun toggleAutoTracking() {
        autoTrackingEnabled = !autoTrackingEnabled
        autoButton.text = if (autoTrackingEnabled) "AUTO: ON" else "AUTO: OFF"
        anchorTracker.reset()
        if (autoTrackingEnabled && overlayView.hasCalibration()) {
            analysisCalibration = overlayView.calibrationPointsSnapshot()
            status("Urmărirea automată este pornită. Telefonul poate corecta mici deplasări ale pânzei.")
        } else {
            status("Urmărirea automată este oprită. Suprapunerea rămâne pe ultima calibrare.")
        }
    }

    private fun resetOverlay() {
        overlayView.clearCalibration()
        overlayView.setTargets(emptyList(), null)
        overlayView.setCompletedCells(emptySet())
        selectedCell = null
        targetCount = 0
        analysisTargets = emptyList()
        analysisCalibration = emptyList()
        anchorTracker.reset()
        progressDetector.resetAll()
        status("Suprapunerea a fost resetată. Alege din nou simbolul.")
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
    }
}
