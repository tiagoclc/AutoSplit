package com.fofinhos.autosplit

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * Serviço do Botão Flutuante (Pílula).
 * Com suporte a arrastar para fechar (círculo com X na base da tela).
 */
class FloatingService : Service() {

    companion object {
        @Volatile
        var isRunning = false
    }

    private data class ScreenSpecs(
        val width: Int, val height: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int
    )

    private lateinit var windowManager: WindowManager
    private lateinit var pillLayout: LinearLayout
    private var closeTargetView: FrameLayout? = null
    private var isOverCloseTarget = false

    private var lastInsetsLeft = -1
    private var lastInsetsTop = -1
    private var lastInsetsRight = -1
    private var lastInsetsBottom = -1
    private var isFirstLayout = true

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getOrientationSuffix(): String {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "_land" else "_port"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        isRunning = true

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)

        pillLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.pill_background)
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER
            scaleX = 0.8f
            scaleY = 0.8f
        }

        val btnAuto = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            setPadding(12, 12, 18, 12)
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(2, 32)
            setBackgroundColor(Color.GRAY)
        }

        val btnSettings = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.WHITE)
            setPadding(24, 16, 16, 16)
        }

        pillLayout.addView(btnAuto)
        pillLayout.addView(divider)
        pillLayout.addView(btnSettings)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val suffix = getOrientationSuffix()
            x = prefs.getInt("floating_x$suffix", 150)
            y = prefs.getInt("floating_y$suffix", 150)
        }

        pillLayout.setOnApplyWindowInsetsListener { _, insets ->
            pillLayout.post { ajustarPosicaoDentroDosLimites() }
            insets
        }

        pillLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var startTime = 0L

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val viewWidth = pillLayout.width
                val viewHeight = pillLayout.height
                if (viewWidth == 0 || viewHeight == 0) return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        startTime = System.currentTimeMillis()
                        mostrarAlvoDeFechamento()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newX = initialX + (event.rawX - initialTouchX).toInt()
                        val newY = initialY + (event.rawY - initialTouchY).toInt()

                        val specs = getGlobalScreenSpecs()
                        val visualWidth = (viewWidth * pillLayout.scaleX).toInt()
                        val visualHeight = (viewHeight * pillLayout.scaleY).toInt()
                        val offsetX = (viewWidth - visualWidth) / 2
                        val offsetY = (viewHeight - visualHeight) / 2

                        params.x = newX.coerceIn(specs.left - offsetX, specs.width - specs.right - viewWidth + offsetX)
                        params.y = newY.coerceIn(specs.top - offsetY, specs.height - specs.bottom - viewHeight + offsetY)

                        windowManager.updateViewLayout(pillLayout, params)
                        
                        checkIfOverCloseTarget(event.rawX, event.rawY, specs.width, specs.height)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isOverCloseTarget) {
                            esconderAlvoDeFechamento()
                            stopSelf()
                            return true
                        }
                        
                        esconderAlvoDeFechamento()

                        val duration = System.currentTimeMillis() - startTime
                        val deltaX = abs(event.rawX - initialTouchX)
                        val deltaY = abs(event.rawY - initialTouchY)

                        if (duration < 300 && deltaX < 15 && deltaY < 15) {
                            val touchXInside = event.x
                            if (touchXInside < (viewWidth / 2)) {
                                val app1 = prefs.getString("app1", "") ?: ""
                                val app2 = prefs.getString("app2", "") ?: ""
                                AutomationManager.rodarFluxo(this@FloatingService, app1, app2)
                            } else {
                                val intent = Intent(this@FloatingService, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    putExtra("FORCE_SETTINGS", true)
                                }
                                startActivity(intent)
                            }
                        } else {
                            val suffix = getOrientationSuffix()
                            getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE).edit().apply {
                                putInt("floating_x$suffix", params.x)
                                putInt("floating_y$suffix", params.y)
                                apply()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(pillLayout, params)

        pillLayout.post {
            isFirstLayout = true
            ajustarPosicaoDentroDosLimites()
        }
    }

    private fun mostrarAlvoDeFechamento() {
        if (closeTargetView != null) return
        
        closeTargetView = FrameLayout(this).apply {
            val size = 200
            layoutParams = FrameLayout.LayoutParams(size, size)
            setBackgroundResource(R.drawable.ic_close_target)
            background?.setTint(Color.argb(180, 255, 255, 255)) // Branco semi-transparente
        }

        val params = WindowManager.LayoutParams(
            200, 200,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100 // Distância da base da tela
        }
        
        windowManager.addView(closeTargetView, params)
    }

    private fun esconderAlvoDeFechamento() {
        closeTargetView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
            closeTargetView = null
        }
        isOverCloseTarget = false
    }

    private fun checkIfOverCloseTarget(x: Float, y: Float, screenWidth: Int, screenHeight: Int) {
        // Coordenadas do centro do alvo na base da tela
        val targetCenterX = screenWidth / 2f
        val targetCenterY = screenHeight - 150f // Aproximado para o centro do ícone de 200px com y=100
        
        val distance = sqrt((x - targetCenterX).toDouble().pow(2.0) + (y - targetCenterY).toDouble().pow(2.0))
        isOverCloseTarget = distance < 180 // Sensibilidade do fechamento
        
        closeTargetView?.background?.setTint(if (isOverCloseTarget) Color.RED else Color.argb(180, 255, 255, 255))
    }

    private fun ajustarPosicaoDentroDosLimites() {
        if (!::pillLayout.isInitialized || pillLayout.parent == null) return

        val params = pillLayout.layoutParams as WindowManager.LayoutParams
        val specs = getGlobalScreenSpecs()

        if (specs.left == lastInsetsLeft && specs.top == lastInsetsTop &&
            specs.right == lastInsetsRight && specs.bottom == lastInsetsBottom && !isFirstLayout) {
            return
        }

        val viewWidth = pillLayout.width
        val viewHeight = pillLayout.height
        if (viewWidth == 0 || viewHeight == 0) return

        val visualWidth = (viewWidth * pillLayout.scaleX).toInt()
        val visualHeight = (viewHeight * pillLayout.scaleY).toInt()
        val offsetX = (viewWidth - visualWidth) / 2
        val offsetY = (viewHeight - visualHeight) / 2

        val minX = specs.left - offsetX
        val maxX = specs.width - specs.right - viewWidth + offsetX
        val minY = specs.top - offsetY
        val maxY = specs.height - specs.bottom - viewHeight + offsetY

        if (isFirstLayout) {
            params.x = params.x.coerceIn(minX, maxX)
            params.y = params.y.coerceIn(minY, maxY)
            isFirstLayout = false
        } else {
            if (specs.top != lastInsetsTop && lastInsetsTop != -1) {
                params.y += (specs.top - lastInsetsTop)
            }
            if (specs.bottom != lastInsetsBottom && lastInsetsBottom != -1) {
                val margemAntigaDeColagem = specs.height - lastInsetsBottom - viewHeight + offsetY
                if (abs(params.y - margemAntigaDeColagem) <= 25) {
                    params.y = maxY
                }
            }
        }

        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(minY, maxY)

        lastInsetsLeft = specs.left
        lastInsetsTop = specs.top
        lastInsetsRight = specs.right
        lastInsetsBottom = specs.bottom

        windowManager.updateViewLayout(pillLayout, params)
    }

    private fun getGlobalScreenSpecs(): ScreenSpecs {
        val display = windowManager.defaultDisplay
        val realMetrics = DisplayMetrics()
        val usableMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(realMetrics)
        @Suppress("DEPRECATION")
        display.getMetrics(usableMetrics)

        val totalWidth = realMetrics.widthPixels
        val totalHeight = realMetrics.heightPixels

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
                val topInset = insets.top.coerceAtLeast(
                    if (totalHeight > usableMetrics.heightPixels && usableMetrics.heightPixels > 0) getStatusBarHeight() else 0
                )
                val diferencaFisicaInferior = totalHeight - usableMetrics.heightPixels - (if (topInset > 0) getStatusBarHeight() else 0)
                val bottomInset = insets.bottom.coerceAtLeast(diferencaFisicaInferior.coerceAtLeast(0))
                return ScreenSpecs(totalWidth, totalHeight, insets.left, topInset, insets.right, bottomInset)
            } catch (e: Exception) {}
        }

        val statusBarHeight = getStatusBarHeight()
        val temBarrasVerticais = totalHeight > usableMetrics.heightPixels
        val temBarrasHorizontais = totalWidth > usableMetrics.widthPixels
        val top = if (temBarrasVerticais) statusBarHeight else 0
        val bottom = if (temBarrasVerticais) (totalHeight - usableMetrics.heightPixels - top).coerceAtLeast(0) else 0
        val right = if (temBarrasHorizontais) (totalWidth - usableMetrics.widthPixels).coerceAtLeast(0) else 0
        return ScreenSpecs(totalWidth, totalHeight, 0, top, right, bottom)
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::pillLayout.isInitialized) {
            pillLayout.post {
                val suffix = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "_land" else "_port"
                val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)
                val params = pillLayout.layoutParams as WindowManager.LayoutParams
                params.x = prefs.getInt("floating_x$suffix", 150)
                params.y = prefs.getInt("floating_y$suffix", 150)
                isFirstLayout = true
                lastInsetsLeft = -1; lastInsetsTop = -1; lastInsetsRight = -1; lastInsetsBottom = -1
                ajustarPosicaoDentroDosLimites()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        esconderAlvoDeFechamento()
        if (::pillLayout.isInitialized) {
            windowManager.removeView(pillLayout)
        }
    }
}
