package com.petitpoint.vision

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: PatternOverlayView
    private lateinit var statusText: TextView

    private var camera: Camera? = null
    private var patternBitmap: Bitmap? = null
    private var patternGrid: PatternGrid? = null

    private var gridRows = 100
    private var gridCols = 100
    private var currentRegion = GridRegion(0, 0, 100, 100)
    private var selectedCell: GridCell? = null
    private var targetCount = 0

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
                // Some document providers do not offer persistable permission; the current read still works.
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

        findViewById<Button>(R.id.loadButton).setOnClickListener {
            patternPickerLauncher.launch(arrayOf("image/*"))
        }
        findViewById<Button>(R.id.gridButton).setOnClickListener { showGridDialog() }
        findViewById<Button>(R.id.symbolButton).setOnClickListener { showSymbolPicker() }
        findViewById<Button>(R.id.calibrateButton).setOnClickListener { beginCalibration() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetOverlay() }

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
            status("Aliniere fixată. $targetCount poziții ale simbolului sunt urmărite în diagramă.")
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

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview
            )
            status(if (patternBitmap == null) "Camera live. Încarcă diagrama Petit Point." else "Camera live. Alege simbolul și calibrează pânza.")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupPinchZoom() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val activeCamera = camera ?: return false
                    val state = activeCamera.cameraInfo.zoomState.value ?: return false
                    val requested = (state.zoomRatio * detector.scaleFactor)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    activeCamera.cameraControl.setZoomRatio(requested)

                    if (overlayView.hasCalibration()) {
                        overlayView.clearCalibration()
                        status("Zoom modificat. Recalibrează cele 4 colțuri ca suprapunerea să rămână exactă.")
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
        overlayView.configureGrid(gridRows, gridCols, currentRegion)
        overlayView.setTargets(emptyList(), null)
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
            container.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                rebuildPatternGrid()
                selectedCell = null
                targetCount = 0
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
        overlayView.beginCalibration()
    }

    private fun resetOverlay() {
        overlayView.clearCalibration()
        overlayView.setTargets(emptyList(), null)
        selectedCell = null
        targetCount = 0
        status("Suprapunerea a fost resetată. Alege din nou simbolul.")
    }

    private fun parsePositive(field: EditText, fallback: Int): Int =
        field.text?.toString()?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

    private fun status(message: String) {
        statusText.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
