package com.example.chineselearning

import android.graphics.*
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // 1. CẤU HÌNH NÉT VẼ
    private var path = Path()
    private var paint = Paint().apply {
        color = Color.parseColor("#7B1FA2") // Màu tím khi viết
        style = Paint.Style.STROKE
        strokeWidth = 25f 
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // Sử dụng font mặc định của hệ thống
    private val defaultTypeface = Typeface.DEFAULT

    private var textPaint = Paint().apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
        alpha = 110
        isAntiAlias = true
        typeface = defaultTypeface
    }

    private var targetText: String = ""
    private var dynamicTextSize: Float = 500f
    private var showGuideText: Boolean = true
    private var animationScale: Float = 1.0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (targetText.isNotEmpty() && showGuideText) {
            adjustTextSize(width.toFloat() - 100f)
            
            val baseSize = dynamicTextSize
            val currentSize = baseSize * animationScale
            textPaint.textSize = currentSize
            textPaint.typeface = defaultTypeface
            
            val xPos = width / 2f
            val yPos = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)

            textPaint.color = Color.LTGRAY
            textPaint.alpha = 110
            
            canvas.drawText(targetText, xPos, yPos, textPaint)
        }

        canvas.drawPath(path, paint)
    }

    private fun adjustTextSize(maxWidth: Float) {
        var fontSize = 550f 
        textPaint.textSize = fontSize
        textPaint.typeface = defaultTypeface
        
        while (textPaint.measureText(targetText) > maxWidth && fontSize > 80f) {
            fontSize -= 10f
            textPaint.textSize = fontSize
        }
        dynamicTextSize = fontSize
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> path.moveTo(x, y)
            MotionEvent.ACTION_MOVE -> path.lineTo(x, y)
        }
        invalidate()
        return true
    }

    fun setTargetText(text: String) {
        this.targetText = text
        this.clear()
        invalidate()
    }

    fun setShowGuideText(show: Boolean) {
        this.showGuideText = show
        invalidate()
    }

    fun clear() {
        path.reset()
        invalidate()
    }

    // 5. THUẬT TOÁN CHẤM ĐIỂM (DỄ HƠN)
    fun calculateScore(): Int {
        if (targetText.isEmpty() || path.isEmpty) return 0

        val size = 200 
        val userBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val targetBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

        val userBounds = RectF()
        path.computeBounds(userBounds, true)
        val canvasUser = Canvas(userBmp)
        val matrix = Matrix()
        matrix.setRectToRect(userBounds, RectF(30f, 30f, size - 30f, size - 30f), Matrix.ScaleToFit.CENTER)
        canvasUser.setMatrix(matrix)
        canvasUser.drawPath(path, paint)

        val canvasTarget = Canvas(targetBmp)
        val samplePaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 15f 
            typeface = defaultTypeface
        }
        
        var sampleFontSize = 180f
        samplePaint.textSize = sampleFontSize
        while (samplePaint.measureText(targetText) > (size - 60f)) {
            sampleFontSize -= 5f
            samplePaint.textSize = sampleFontSize
        }
        
        canvasTarget.drawText(targetText, size / 2f, (size / 2f) - ((samplePaint.descent() + samplePaint.ascent()) / 2f), samplePaint)

        var matches = 0
        var totalTargetPixels = 0
        for (x in 0 until size step 2) {
            for (y in 0 until size step 2) {
                val isTarget = Color.alpha(targetBmp.getPixel(x, y)) > 50
                if (isTarget) {
                    totalTargetPixels++
                    if (hasUserPixelNearby(userBmp, x, y, size)) {
                        matches++
                    }
                }
            }
        }

        if (totalTargetPixels == 0) return 0
        
        val rawScore = (matches.toFloat() / totalTargetPixels) * 220
        return rawScore.toInt().coerceAtMost(100)
    }

    private fun hasUserPixelNearby(bmp: Bitmap, x: Int, y: Int, size: Int): Boolean {
        val radius = 2 
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until size && ny in 0 until size) {
                    if (Color.alpha(bmp.getPixel(nx, ny)) > 50) return true
                }
            }
        }
        return false
    }
}