package com.fofinhos.autosplit

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Serviço que cria um botão flutuante em forma de pílula com duas funções.
 */
class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var pillLayout: LinearLayout

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)

        // Criar o Layout da Pílula (Reduzido para 80% do original)
        pillLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.pill_background)
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER
            scaleX = 0.8f
            scaleY = 0.8f
        }

        // Lado Esquerdo: Botão de Automação (Rodar Fluxo) - Ícone Cinzento (mesma cor da engrenagem)
        val btnAuto = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE) // Cor padrão para ícones de sistema, igual à engrenagem
            setPadding(16, 16, 24, 16)
        }

        // Divisor central (opcional)
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(2, 40)
            setBackgroundColor(Color.GRAY)
        }

        // Lado Direito: Botão de Configurações
        val btnSettings = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.WHITE)
            setPadding(30, 20, 20, 20)
        }

        pillLayout.addView(btnAuto)
        pillLayout.addView(divider)
        pillLayout.addView(btnSettings)

        // Configurações da Janela para ficar por cima de TUDO
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
            
            // Obter dimensões da tela para limites
            val (screenWidth, screenHeight) = getScreenDimensions()

            // Carrega posição persistente
            x = prefs.getInt("floating_x", 150)
            y = prefs.getInt("floating_y", 150)
            
            // Proteção para não iniciar fora da tela (considerando escala 0.8)
            x = x.coerceIn(0, screenWidth)
            y = y.coerceIn(0, screenHeight)
        }

        // Lógica de arrastar a pílula com suporte a cliques manuais
        pillLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var startTime = 0L

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val (screenWidth, screenHeight) = getScreenDimensions()

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
                        
                        // Impedir sair da tela
                        // Consideramos a largura e altura do pillLayout multiplicada pela escala
                        val scaledWidth = (pillLayout.width * pillLayout.scaleX).toInt()
                        val scaledHeight = (pillLayout.height * pillLayout.scaleY).toInt()
                        
                        params.x = newX.coerceIn(0, screenWidth - scaledWidth-40)
                        params.y = newY.coerceIn(0, screenHeight - scaledHeight-40)

                        windowManager.updateViewLayout(pillLayout, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - startTime
                        val deltaX = abs(event.rawX - initialTouchX)
                        val deltaY = abs(event.rawY - initialTouchY)

                        // Se o movimento foi pequeno e rápido, tratamos como clique manual
                        if (duration < 300 && deltaX < 15 && deltaY < 15) {
                            val touchXInside = event.x
                            if (touchXInside < (pillLayout.width * 0.8) / 2) {
                                // Lado Esquerdo: Automação
                                val app1 = prefs.getString("app1", "") ?: ""
                                val app2 = prefs.getString("app2", "") ?: ""
                                val tapX = prefs.getInt("tap_x", 1712)
                                val tapY = prefs.getInt("tap_y", 1044)
                                AutomationManager.rodarFluxo(this@FloatingService, app1, app2, tapX, tapY)
                            } else {
                                // Lado Direito: Configurações
                                val intent = Intent(this@FloatingService, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    putExtra("FORCE_SETTINGS", true)
                                }
                                startActivity(intent)
                            }
                        } else {
                            // Salvar posição após arrastar
                            getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE).edit().apply {
                                putInt("floating_x", params.x)
                                putInt("floating_y", params.y)
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
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::pillLayout.isInitialized) {
            windowManager.removeView(pillLayout)
        }
    }
}
