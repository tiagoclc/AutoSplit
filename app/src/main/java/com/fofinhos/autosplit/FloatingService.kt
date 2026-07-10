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
import android.widget.LinearLayout
import android.widget.ImageView
import kotlin.math.abs

/**
 * Serviço do Botão Flutuante (Pílula).
 * Correção de Bouncing e alinhamento milimétrico reativo às barras do Android.
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

    // Histórico global das barras para evitar loops de reposicionamento (Anti-Bouncing)
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

        // Inicializa o Contentor com a escala visual de 0.8f
        pillLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.pill_background)
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER
            scaleX = 0.8f
            scaleY = 0.8f
        }

        // Lado Esquerdo: Executar fluxo
        val btnAuto = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            setPadding(12, 12, 18, 12)
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(2, 32)
            setBackgroundColor(Color.GRAY)
        }

        // Lado Direito: Configurações
        val btnSettings = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.WHITE)
            setPadding(24, 16, 16, 16)
        }

        pillLayout.addView(btnAuto)
        pillLayout.addView(divider)
        pillLayout.addView(btnSettings)

        // Parâmetros de Janela robustos
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

        // Gatilho do Listener: Usado apenas como sinalizador de evento do sistema
        pillLayout.setOnApplyWindowInsetsListener { _, insets ->
            pillLayout.post {
                ajustarPosicaoDentroDosLimites()
            }
            insets
        }

        // Gestão de Arrasto Manual Livre de Conflitos
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

                        // Limita o movimento baseado na geometria física do momento
                        params.x = newX.coerceIn(specs.left - offsetX, specs.width - specs.right - viewWidth + offsetX)
                        params.y = newY.coerceIn(specs.top - offsetY, specs.height - specs.bottom - viewHeight + offsetY)

                        windowManager.updateViewLayout(pillLayout, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
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

    /**
     * Centraliza a inteligência de posicionamento estrito.
     * Cancela atualizações redundantes removendo o efeito "bouncing".
     */
    private fun ajustarPosicaoDentroDosLimites() {
        if (!::pillLayout.isInitialized || pillLayout.parent == null) return

        val params = pillLayout.layoutParams as WindowManager.LayoutParams
        val specs = getGlobalScreenSpecs()

        // BLOQUEIO ANTI-BOUNCING: Se o estado real das barras não mudou, interrompe o ciclo
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

        // Fronteiras matemáticas exatas compensando os 10% de margem invisível do Scale
        val minX = specs.left - offsetX
        val maxX = specs.width - specs.right - viewWidth + offsetX
        val minY = specs.top - offsetY
        val maxY = specs.height - specs.bottom - viewHeight + offsetY

        if (isFirstLayout) {
            params.x = params.x.coerceIn(minX, maxX)
            params.y = params.y.coerceIn(minY, maxY)
            isFirstLayout = false
        } else {
            // Se a barra superior colapsou ou expandiu, ajusta o delta correspondente
            if (specs.top != lastInsetsTop && lastInsetsTop != -1) {
                params.y += (specs.top - lastInsetsTop)
            }
            // Se o botão estava colado na barra inferior e ela subiu/desceu, acompanha o frame
            if (specs.bottom != lastInsetsBottom && lastInsetsBottom != -1) {
                val margemAntigaDeColagem = specs.height - lastInsetsBottom - viewHeight + offsetY
                if (abs(params.y - margemAntigaDeColagem) <= 25) {
                    params.y = maxY
                }
            }
        }

        // Aplicação sob coação estrita das margens de segurança
        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(minY, maxY)

        // Salva o estado atual para o próximo ciclo comparativo
        lastInsetsLeft = specs.left
        lastInsetsTop = specs.top
        lastInsetsRight = specs.right
        lastInsetsBottom = specs.bottom

        windowManager.updateViewLayout(pillLayout, params)
    }

    /**
     * Mapeia de forma infalível a resolução do visor e o tamanho das barras de sistema
     * cruzando métricas em tempo de execução. Funciona perfeitamente do Android 10 ao 14+.
     */
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

                // Correção para Roms integradas/customizadas: validação cruzada do Top e Bottom
                val topInset = insets.top.coerceAtLeast(
                    if (totalHeight > usableMetrics.heightPixels && usableMetrics.heightPixels > 0) getStatusBarHeight() else 0
                )
                val diferencaFisicaInferior = totalHeight - usableMetrics.heightPixels - (if (topInset > 0) getStatusBarHeight() else 0)
                val bottomInset = insets.bottom.coerceAtLeast(diferencaFisicaInferior.coerceAtLeast(0))

                return ScreenSpecs(totalWidth, totalHeight, insets.left, topInset, insets.right, bottomInset)
            } catch (e: Exception) {
                // Se falhar o WindowMetrics do Android 11, salta para o fallback clássico abaixo
            }
        }

        // Algoritmo de Fallback Clássico (Altamente fiável para Android 10 e sistemas BYD DiLink)
        val statusBarHeight = getStatusBarHeight()
        val temBarrasVerticais = totalHeight > usableMetrics.heightPixels
        val temBarrasHorizontais = totalWidth > usableMetrics.widthPixels

        val top = if (temBarrasVerticais) statusBarHeight else 0
        val bottom = if (temBarrasVerticais) (totalHeight - usableMetrics.heightPixels - top).coerceAtLeast(0) else 0
        val right = if (temBarrasHorizontais) (totalWidth - usableMetrics.widthPixels).coerceAtLeast(0) else 0
        val left = 0

        return ScreenSpecs(totalWidth, totalHeight, left, top, right, bottom)
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::pillLayout.isInitialized) {
            pillLayout.post {
                val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)
                val suffix = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "_land" else "_port"
                val params = pillLayout.layoutParams as WindowManager.LayoutParams

                params.x = prefs.getInt("floating_x$suffix", 150)
                params.y = prefs.getInt("floating_y$suffix", 150)

                // Força o reset para aceitar as dimensões da nova orientação sem misturar deltas
                isFirstLayout = true
                lastInsetsLeft = -1
                lastInsetsTop = -1
                lastInsetsRight = -1
                lastInsetsBottom = -1

                ajustarPosicaoDentroDosLimites()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (::pillLayout.isInitialized) {
            windowManager.removeView(pillLayout)
        }
    }
}