package com.petitpoint.vision.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Catalogul fix al celor 59 de cartele confirmate din goblen.
 *
 * Scopul principal este ca utilizatorul să caute NUMĂRUL cartelei, iar pe pânză să vadă
 * SIMBOLUL grafic. Reprezentările sunt vectoriale și pot fi înlocuite de un simbol învățat
 * direct din pagina tipărită; simbolul învățat are prioritate la potrivire.
 */
object BuiltInSymbolCatalog {
    data class Definition(val code: Int, val name: String)

    val definitions: List<Definition> = listOf(
        Definition(1, "bulină plină"), Definition(2, "romb plin"),
        Definition(3, "ancoră / T stilizat"), Definition(4, "săgeată jos"),
        Definition(5, "triunghi contur"), Definition(6, "arc / U întors"),
        Definition(7, "8 alb în cerc negru"), Definition(8, "dolar"),
        Definition(9, "asterisc"), Definition(10, "triunghi plin jos"),
        Definition(11, "pătrat plin"), Definition(12, "bulină cu codiță"),
        Definition(13, "floare plină"), Definition(14, "romb dublu"),
        Definition(15, "Z"), Definition(16, "colț curbat"),
        Definition(17, "semicerc plin"), Definition(18, "floare 4 petale"),
        Definition(19, "potcoavă"), Definition(20, "semilună"),
        Definition(21, "pătrat plin cu punct alb"), Definition(22, "cruce simplă"),
        Definition(23, "clepsidră plină"), Definition(24, "dreptunghi vertical"),
        Definition(25, "tablă de șah"), Definition(26, "două romburi"),
        Definition(27, "stea plină"), Definition(28, "săgeată sus"),
        Definition(29, "inimă plină"), Definition(30, "bară oblică"),
        Definition(31, "pătrat plin mic"), Definition(32, "cerc cu plus"),
        Definition(33, "oval vertical"), Definition(34, "spirală"),
        Definition(35, "acoperiș / triunghi deschis"), Definition(36, "inimă contur"),
        Definition(37, "pică plină"), Definition(38, "trifoi contur"),
        Definition(39, "pătrat contur cu diagonală /"), Definition(40, "X gros"),
        Definition(41, "treflă plină"), Definition(42, "raze"),
        Definition(43, "X bloc"), Definition(44, "fundă / clepsidră"),
        Definition(45, "val"), Definition(46, "nod cu 4 bucle"),
        Definition(47, "diez"), Definition(48, "romb contur"),
        Definition(49, "două linii oblice"), Definition(50, "pătrat hașurat"),
        Definition(51, "cruce groasă plină"), Definition(52, "ramură spre stânga"),
        Definition(53, "soare contur"), Definition(54, "linie orizontală"),
        Definition(55, "grilaj dublu"), Definition(56, "săgeată groasă jos"),
        Definition(57, "pătrat contur cu diagonală \\"), Definition(58, "cruce rotunjită"),
        Definition(59, "pătrat contur punctat")
    )

    fun definition(code: Int): Definition? = definitions.firstOrNull { it.code == code }

    fun render(code: Int, size: Int = 112): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawSymbol(canvas, code, size.toFloat())
        return bitmap
    }

    fun fingerprint(code: Int): BooleanArray {
        val bitmap = render(code, 112)
        return try {
            SymbolFingerprint.fromRect(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawSymbol(canvas: Canvas, code: Int, s: Float) {
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = s * 0.075f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val thin = Paint(stroke).apply { strokeWidth = s * 0.045f }
        val cx = s / 2f
        val cy = s / 2f

        fun text(value: String, scale: Float = 0.64f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                textSize = s * scale
                typeface = Typeface.DEFAULT_BOLD
            }
            val fm = p.fontMetrics
            canvas.drawText(value, cx, cy - (fm.ascent + fm.descent) / 2f, p)
        }

        when (code) {
            1 -> canvas.drawCircle(cx, cy, s * 0.23f, black)
            2 -> diamond(canvas, cx, cy, s * 0.29f, black)
            3 -> anchor(canvas, s, stroke)
            4 -> arrow(canvas, cx, cy, s, down = true, fill = black)
            5 -> triangle(canvas, s, down = false, fill = null, stroke = stroke)
            6 -> arch(canvas, s, stroke)
            7 -> {
                canvas.drawCircle(cx, cy, s * 0.31f, black)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = s * 0.48f; typeface = Typeface.DEFAULT_BOLD
                }
                val fm = p.fontMetrics
                canvas.drawText("8", cx, cy - (fm.ascent + fm.descent) / 2f, p)
            }
            8 -> text("$")
            9 -> asterisk(canvas, s, stroke, 8)
            10 -> triangle(canvas, s, down = true, fill = black, stroke = null)
            11 -> canvas.drawRect(s * 0.29f, s * 0.29f, s * 0.71f, s * 0.71f, black)
            12 -> {
                canvas.drawCircle(s * 0.43f, s * 0.43f, s * 0.18f, black)
                canvas.drawLine(s * 0.55f, s * 0.55f, s * 0.76f, s * 0.76f, stroke)
            }
            13 -> flower(canvas, s, black, petals = 5, outlined = false)
            14 -> {
                diamond(canvas, s * 0.41f, cy, s * 0.20f, black)
                diamond(canvas, s * 0.59f, cy, s * 0.20f, black)
            }
            15 -> text("Z", 0.62f)
            16 -> {
                canvas.drawArc(RectF(s * 0.23f, s * 0.23f, s * 0.78f, s * 0.78f), 195f, 95f, false, stroke)
                canvas.drawLine(s * 0.27f, s * 0.58f, s * 0.27f, s * 0.78f, stroke)
            }
            17 -> canvas.drawArc(RectF(s * 0.24f, s * 0.25f, s * 0.76f, s * 0.77f), 180f, 180f, true, black)
            18 -> flower(canvas, s, black, petals = 4, outlined = false)
            19 -> canvas.drawArc(RectF(s * 0.25f, s * 0.22f, s * 0.75f, s * 0.78f), 185f, 170f, false, stroke)
            20 -> crescent(canvas, s, black)
            21 -> {
                canvas.drawRect(s * 0.25f, s * 0.25f, s * 0.75f, s * 0.75f, black)
                val white = Paint(black).apply { color = Color.WHITE }
                canvas.drawCircle(cx, cy, s * 0.10f, white)
            }
            22 -> plus(canvas, s, stroke)
            23 -> hourglass(canvas, s, black, narrow = true)
            24 -> canvas.drawRoundRect(RectF(s * 0.38f, s * 0.20f, s * 0.62f, s * 0.80f), s * 0.04f, s * 0.04f, black)
            25 -> checker(canvas, s, black)
            26 -> {
                diamond(canvas, s * 0.36f, cy, s * 0.18f, black)
                diamond(canvas, s * 0.64f, cy, s * 0.18f, black)
            }
            27 -> star(canvas, s, black)
            28 -> arrow(canvas, cx, cy, s, down = false, fill = black)
            29 -> heart(canvas, s, black, outlined = false)
            30 -> canvas.drawLine(s * 0.30f, s * 0.72f, s * 0.70f, s * 0.28f, Paint(stroke).apply { strokeWidth = s * 0.18f })
            31 -> canvas.drawRect(s * 0.33f, s * 0.33f, s * 0.67f, s * 0.67f, black)
            32 -> {
                canvas.drawCircle(cx, cy, s * 0.29f, stroke)
                plus(canvas, s * 0.82f, thin, offset = s * 0.09f)
            }
            33 -> canvas.drawOval(RectF(s * 0.37f, s * 0.19f, s * 0.63f, s * 0.81f), black)
            34 -> spiral(canvas, s, thin)
            35 -> {
                val p = Path().apply { moveTo(s * 0.25f, s * 0.67f); lineTo(cx, s * 0.28f); lineTo(s * 0.75f, s * 0.67f) }
                canvas.drawPath(p, stroke)
            }
            36 -> heart(canvas, s, stroke, outlined = true)
            37 -> spade(canvas, s, black)
            38 -> flower(canvas, s, stroke, petals = 4, outlined = true)
            39 -> squareDiagonal(canvas, s, stroke, forward = true)
            40 -> xMark(canvas, s, strokeWidth = s * 0.15f)
            41 -> club(canvas, s, black)
            42 -> rays(canvas, s, thin)
            43 -> {
                xMark(canvas, s, strokeWidth = s * 0.11f)
                canvas.drawRect(s * 0.43f, s * 0.43f, s * 0.57f, s * 0.57f, black)
            }
            44 -> hourglass(canvas, s, black, narrow = false)
            45 -> wave(canvas, s, stroke)
            46 -> knot(canvas, s, stroke)
            47 -> text("#", 0.60f)
            48 -> diamond(canvas, cx, cy, s * 0.30f, stroke)
            49 -> {
                canvas.drawLine(s * 0.29f, s * 0.72f, s * 0.57f, s * 0.28f, stroke)
                canvas.drawLine(s * 0.45f, s * 0.72f, s * 0.73f, s * 0.28f, stroke)
            }
            50 -> hatchedSquare(canvas, s, stroke, thin)
            51 -> thickPlus(canvas, s, black)
            52 -> branchLeft(canvas, s, stroke)
            53 -> sun(canvas, s, stroke, thin)
            54 -> canvas.drawLine(s * 0.27f, cy, s * 0.73f, cy, Paint(stroke).apply { strokeWidth = s * 0.09f })
            55 -> gridHash(canvas, s, thin)
            56 -> arrow(canvas, cx, cy, s, down = true, fill = black, thick = true)
            57 -> squareDiagonal(canvas, s, stroke, forward = false)
            58 -> roundedCross(canvas, s, black)
            59 -> dottedSquare(canvas, s, stroke, black)
            else -> text(code.toString(), 0.50f)
        }
    }

    private fun diamond(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }
        c.drawPath(path, p)
    }

    private fun triangle(c: Canvas, s: Float, down: Boolean, fill: Paint?, stroke: Paint?) {
        val path = Path()
        if (down) {
            path.moveTo(s * 0.25f, s * 0.31f); path.lineTo(s * 0.75f, s * 0.31f); path.lineTo(s * 0.50f, s * 0.74f)
        } else {
            path.moveTo(s * 0.25f, s * 0.69f); path.lineTo(s * 0.75f, s * 0.69f); path.lineTo(s * 0.50f, s * 0.26f)
        }
        path.close()
        fill?.let { c.drawPath(path, it) }
        stroke?.let { c.drawPath(path, it) }
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, s: Float, down: Boolean, fill: Paint, thick: Boolean = false) {
        val w = if (thick) 0.18f else 0.12f
        val path = Path()
        if (down) {
            path.moveTo(s * (0.5f - w / 2f), s * 0.23f); path.lineTo(s * (0.5f + w / 2f), s * 0.23f)
            path.lineTo(s * (0.5f + w / 2f), s * 0.55f); path.lineTo(s * 0.70f, s * 0.55f)
            path.lineTo(cx, s * 0.79f); path.lineTo(s * 0.30f, s * 0.55f); path.lineTo(s * (0.5f - w / 2f), s * 0.55f)
        } else {
            path.moveTo(cx, s * 0.21f); path.lineTo(s * 0.70f, s * 0.45f); path.lineTo(s * (0.5f + w / 2f), s * 0.45f)
            path.lineTo(s * (0.5f + w / 2f), s * 0.77f); path.lineTo(s * (0.5f - w / 2f), s * 0.77f)
            path.lineTo(s * (0.5f - w / 2f), s * 0.45f); path.lineTo(s * 0.30f, s * 0.45f)
        }
        path.close(); c.drawPath(path, fill)
    }

    private fun anchor(c: Canvas, s: Float, p: Paint) {
        c.drawLine(s * 0.50f, s * 0.25f, s * 0.50f, s * 0.68f, p)
        c.drawLine(s * 0.36f, s * 0.34f, s * 0.64f, s * 0.34f, p)
        c.drawArc(RectF(s * 0.28f, s * 0.48f, s * 0.72f, s * 0.76f), 0f, 180f, false, p)
        c.drawLine(s * 0.28f, s * 0.62f, s * 0.23f, s * 0.55f, p)
        c.drawLine(s * 0.72f, s * 0.62f, s * 0.77f, s * 0.55f, p)
    }

    private fun arch(c: Canvas, s: Float, p: Paint) {
        c.drawArc(RectF(s * 0.25f, s * 0.25f, s * 0.75f, s * 0.70f), 180f, 180f, false, p)
        c.drawLine(s * 0.25f, s * 0.48f, s * 0.25f, s * 0.75f, p)
        c.drawLine(s * 0.75f, s * 0.48f, s * 0.75f, s * 0.75f, p)
    }

    private fun asterisk(c: Canvas, s: Float, p: Paint, rays: Int) {
        val cx = s / 2f; val cy = s / 2f; val r = s * 0.28f
        repeat(rays / 2) { i ->
            val a = PI * i / (rays / 2).toDouble()
            val dx = (cos(a) * r).toFloat(); val dy = (sin(a) * r).toFloat()
            c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, p)
        }
    }

    private fun flower(c: Canvas, s: Float, p: Paint, petals: Int, outlined: Boolean) {
        val cx = s / 2f; val cy = s / 2f; val pr = s * 0.14f; val orbit = s * 0.16f
        repeat(petals) { i ->
            val a = 2.0 * PI * i / petals - PI / 2.0
            c.drawCircle(cx + (cos(a) * orbit).toFloat(), cy + (sin(a) * orbit).toFloat(), pr, p)
        }
        val centerPaint = Paint(p)
        if (outlined) centerPaint.style = Paint.Style.STROKE
        c.drawCircle(cx, cy, s * 0.10f, centerPaint)
    }

    private fun crescent(c: Canvas, s: Float, black: Paint) {
        c.drawCircle(s * 0.49f, s * 0.50f, s * 0.28f, black)
        val white = Paint(black).apply { color = Color.WHITE }
        c.drawCircle(s * 0.60f, s * 0.43f, s * 0.25f, white)
    }

    private fun plus(c: Canvas, s: Float, p: Paint, offset: Float = 0f) {
        val ss = s - offset * 2f; val cx = offset + ss / 2f; val cy = offset + ss / 2f; val r = ss * 0.27f
        c.drawLine(cx - r, cy, cx + r, cy, p); c.drawLine(cx, cy - r, cx, cy + r, p)
    }

    private fun hourglass(c: Canvas, s: Float, p: Paint, narrow: Boolean) {
        val x = if (narrow) 0.34f else 0.27f
        val path = Path().apply {
            moveTo(s * x, s * 0.25f); lineTo(s * (1f - x), s * 0.25f); lineTo(s * 0.56f, s * 0.50f)
            lineTo(s * (1f - x), s * 0.75f); lineTo(s * x, s * 0.75f); lineTo(s * 0.44f, s * 0.50f); close()
        }
        c.drawPath(path, p)
    }

    private fun checker(c: Canvas, s: Float, p: Paint) {
        val l = s * 0.27f; val cell = s * 0.153f
        for (r in 0..2) for (col in 0..2) if ((r + col) % 2 == 0) {
            c.drawRect(l + col * cell, l + r * cell, l + (col + 1) * cell, l + (r + 1) * cell, p)
        }
    }

    private fun star(c: Canvas, s: Float, p: Paint) {
        val path = Path(); val cx = s / 2f; val cy = s / 2f
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) s * 0.30f else s * 0.13f
            val a = -PI / 2 + i * PI / 5
            val x = cx + (cos(a) * r).toFloat(); val y = cy + (sin(a) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); c.drawPath(path, p)
    }

    private fun heart(c: Canvas, s: Float, p: Paint, outlined: Boolean) {
        val path = Path().apply {
            moveTo(s * 0.50f, s * 0.76f)
            cubicTo(s * 0.18f, s * 0.55f, s * 0.20f, s * 0.27f, s * 0.36f, s * 0.27f)
            cubicTo(s * 0.47f, s * 0.27f, s * 0.50f, s * 0.37f, s * 0.50f, s * 0.37f)
            cubicTo(s * 0.50f, s * 0.37f, s * 0.53f, s * 0.27f, s * 0.64f, s * 0.27f)
            cubicTo(s * 0.80f, s * 0.27f, s * 0.82f, s * 0.55f, s * 0.50f, s * 0.76f)
            close()
        }
        val pp = Paint(p); pp.style = if (outlined) Paint.Style.STROKE else Paint.Style.FILL
        c.drawPath(path, pp)
    }

    private fun spiral(c: Canvas, s: Float, p: Paint) {
        val path = Path(); val cx = s / 2f; val cy = s / 2f
        for (i in 0..90) {
            val t = i / 90f; val a = t * (PI * 4.5); val r = s * (0.03f + 0.26f * t)
            val x = cx + (cos(a) * r).toFloat(); val y = cy + (sin(a) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, p)
    }

    private fun spade(c: Canvas, s: Float, p: Paint) {
        val path = Path().apply {
            moveTo(s * 0.50f, s * 0.22f); cubicTo(s * 0.42f, s * 0.37f, s * 0.25f, s * 0.43f, s * 0.28f, s * 0.59f)
            cubicTo(s * 0.31f, s * 0.72f, s * 0.45f, s * 0.68f, s * 0.50f, s * 0.58f)
            cubicTo(s * 0.55f, s * 0.68f, s * 0.69f, s * 0.72f, s * 0.72f, s * 0.59f)
            cubicTo(s * 0.75f, s * 0.43f, s * 0.58f, s * 0.37f, s * 0.50f, s * 0.22f); close()
        }
        c.drawPath(path, p); c.drawRect(s * 0.46f, s * 0.57f, s * 0.54f, s * 0.79f, p)
    }

    private fun squareDiagonal(c: Canvas, s: Float, p: Paint, forward: Boolean) {
        c.drawRect(s * 0.27f, s * 0.27f, s * 0.73f, s * 0.73f, p)
        if (forward) c.drawLine(s * 0.29f, s * 0.71f, s * 0.71f, s * 0.29f, p)
        else c.drawLine(s * 0.29f, s * 0.29f, s * 0.71f, s * 0.71f, p)
    }

    private fun xMark(c: Canvas, s: Float, strokeWidth: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; this.strokeWidth = strokeWidth; strokeCap = Paint.Cap.SQUARE }
        c.drawLine(s * 0.28f, s * 0.28f, s * 0.72f, s * 0.72f, p); c.drawLine(s * 0.72f, s * 0.28f, s * 0.28f, s * 0.72f, p)
    }

    private fun club(c: Canvas, s: Float, p: Paint) {
        c.drawCircle(s * 0.50f, s * 0.34f, s * 0.15f, p); c.drawCircle(s * 0.36f, s * 0.51f, s * 0.15f, p); c.drawCircle(s * 0.64f, s * 0.51f, s * 0.15f, p)
        c.drawRect(s * 0.46f, s * 0.50f, s * 0.54f, s * 0.75f, p); c.drawRect(s * 0.39f, s * 0.70f, s * 0.61f, s * 0.77f, p)
    }

    private fun rays(c: Canvas, s: Float, p: Paint) {
        val cx = s / 2f; val cy = s / 2f
        repeat(7) { i ->
            val a = -PI * 0.85 + i * (PI * 0.28)
            c.drawLine(cx + (cos(a) * s * 0.10).toFloat(), cy + (sin(a) * s * 0.10).toFloat(), cx + (cos(a) * s * 0.31).toFloat(), cy + (sin(a) * s * 0.31).toFloat(), p)
        }
    }

    private fun wave(c: Canvas, s: Float, p: Paint) {
        val path = Path().apply { moveTo(s * 0.23f, s * 0.55f); cubicTo(s * 0.34f, s * 0.30f, s * 0.42f, s * 0.78f, s * 0.53f, s * 0.53f); cubicTo(s * 0.62f, s * 0.33f, s * 0.70f, s * 0.68f, s * 0.78f, s * 0.47f) }
        c.drawPath(path, p)
    }

    private fun knot(c: Canvas, s: Float, p: Paint) {
        val cx = s / 2f; val cy = s / 2f
        c.drawOval(RectF(s * 0.25f, s * 0.40f, s * 0.50f, s * 0.60f), p); c.drawOval(RectF(s * 0.50f, s * 0.40f, s * 0.75f, s * 0.60f), p)
        c.drawOval(RectF(s * 0.40f, s * 0.25f, s * 0.60f, s * 0.50f), p); c.drawOval(RectF(s * 0.40f, s * 0.50f, s * 0.60f, s * 0.75f), p)
        c.drawCircle(cx, cy, s * 0.05f, Paint(p).apply { style = Paint.Style.FILL })
    }

    private fun hatchedSquare(c: Canvas, s: Float, outline: Paint, hatch: Paint) {
        val l = s * 0.27f; val r = s * 0.73f; c.drawRect(l, l, r, r, outline)
        var x = l - s * 0.15f
        while (x < r) { c.drawLine(x, r, x + s * 0.46f, l, hatch); x += s * 0.11f }
    }

    private fun thickPlus(c: Canvas, s: Float, p: Paint) {
        c.drawRect(s * 0.42f, s * 0.22f, s * 0.58f, s * 0.78f, p); c.drawRect(s * 0.22f, s * 0.42f, s * 0.78f, s * 0.58f, p)
    }

    private fun branchLeft(c: Canvas, s: Float, p: Paint) {
        c.drawLine(s * 0.72f, s * 0.50f, s * 0.27f, s * 0.50f, p)
        c.drawLine(s * 0.27f, s * 0.50f, s * 0.40f, s * 0.36f, p); c.drawLine(s * 0.27f, s * 0.50f, s * 0.40f, s * 0.64f, p)
        c.drawLine(s * 0.50f, s * 0.50f, s * 0.58f, s * 0.34f, p); c.drawLine(s * 0.50f, s * 0.50f, s * 0.58f, s * 0.66f, p)
    }

    private fun sun(c: Canvas, s: Float, ring: Paint, ray: Paint) {
        val cx = s / 2f; val cy = s / 2f; c.drawCircle(cx, cy, s * 0.16f, ring)
        repeat(10) { i -> val a = 2.0 * PI * i / 10.0; c.drawLine(cx + (cos(a) * s * 0.22).toFloat(), cy + (sin(a) * s * 0.22).toFloat(), cx + (cos(a) * s * 0.32).toFloat(), cy + (sin(a) * s * 0.32).toFloat(), ray) }
    }

    private fun gridHash(c: Canvas, s: Float, p: Paint) {
        for (f in listOf(0.36f, 0.48f, 0.60f)) { c.drawLine(s * f, s * 0.25f, s * (f + 0.08f), s * 0.75f, p) }
        for (f in listOf(0.38f, 0.52f, 0.66f)) { c.drawLine(s * 0.25f, s * f, s * 0.75f, s * (f - 0.08f), p) }
    }

    private fun roundedCross(c: Canvas, s: Float, p: Paint) {
        val r = s * 0.13f; val cx = s / 2f; val cy = s / 2f
        c.drawRoundRect(RectF(s * 0.42f, s * 0.22f, s * 0.58f, s * 0.78f), r, r, p)
        c.drawRoundRect(RectF(s * 0.22f, s * 0.42f, s * 0.78f, s * 0.58f), r, r, p)
    }

    private fun dottedSquare(c: Canvas, s: Float, outline: Paint, dot: Paint) {
        c.drawRect(s * 0.26f, s * 0.26f, s * 0.74f, s * 0.74f, outline)
        for (r in 0..2) for (col in 0..2) c.drawCircle(s * (0.34f + col * 0.16f), s * (0.34f + r * 0.16f), s * 0.025f, dot)
    }
}
