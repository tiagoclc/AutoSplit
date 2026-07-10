package com.fofinhos.autosplit

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.*

class TouchCaptureAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var captureView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mostrarOverlayDeCaptura()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun mostrarOverlayDeCaptura() {
        if (captureView != null) return

        captureView = FrameLayout(this).apply {
            // Fundo escuro translúcido para o usuário perceber que o modo de captura está ativo
            setBackgroundColor(Color.argb(100, 0, 0, 0))

            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val finalX = event.rawX.toInt()
                    val finalY = event.rawY.toInt()

                    getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE).edit().apply {
                        putInt("tap_x", finalX)
                        putInt("tap_y", finalY)
                        apply()
                    }

                    Toast.makeText(this@TouchCaptureAccessibilityService, "Capturado: X=$finalX, Y=$finalY", Toast.LENGTH_SHORT).show()

                    sendBroadcast(Intent("com.fofinhos.autosplit.COORDINATES_CAPTURED").apply {
                        putExtra("x", finalX)
                        putExtra("y", finalY)
                    })

                    encerrarCaptura()
                }
                true
            }
        }

        // CORREÇÃO PARA DILINK 3.0: Descobrir a resolução real de hardware do ecrã do carro
        val displayMetrics = DisplayMetrics()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            displayMetrics.widthPixels = bounds.width()
            displayMetrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        }

        val realWidth = displayMetrics.widthPixels
        val realHeight = displayMetrics.heightPixels

        // TYPE_ACCESSIBILITY_OVERLAY combinado com dimensões físicas estritas força a sobreposição
        // absoluta sobre a barra do sistema (ar condicionado / botões nativos da BYD)
        val params = WindowManager.LayoutParams(
            realWidth,  // Força a largura real do hardware
            realHeight, // Força a altura real do hardware, cobrindo a barra inferior
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // Removido FLAG_LAYOUT_INSET_DECOR para não recuar perante decorações
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(captureView, params)
        iniciarContador()
    }

    private fun iniciarContador() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            val maxWaitMs = 10000L
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                val elapsed = System.currentTimeMillis() - startTime
                val remainingSec = ((maxWaitMs - elapsed) / 1000).toInt() + 1

                sendBroadcast(Intent("com.fofinhos.autosplit.CAPTURE_COUNTDOWN").apply {
                    putExtra("seconds", remainingSec)
                })
                delay(1000)
            }

            sendBroadcast(Intent("com.fofinhos.autosplit.CAPTURE_COUNTDOWN").apply {
                putExtra("seconds", 0)
            })

            withContext(Dispatchers.Main) {
                Toast.makeText(this@TouchCaptureAccessibilityService, "Tempo de captura esgotado.", Toast.LENGTH_SHORT).show()
            }
            encerrarCaptura()
        }
    }

    private fun encerrarCaptura() {
        timerJob?.cancel()
        captureView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        captureView = null

        // Desativa a acessibilidade após o uso para não consumir recursos do DiLink
        CoroutineScope(Dispatchers.IO).launch {
            AccessibilityAdbHelper.desativarAcessibilidadeViaAdb(AdbShellExecutor(this@TouchCaptureAccessibilityService))
            disableSelf()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Sem uso necessário
    }

    override fun onInterrupt() {
        encerrarCaptura()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        encerrarCaptura()
    }
}

/**
 * Objeto auxiliar para ligar e desligar o serviço de acessibilidade de forma invisível via ADB Loopback.
 */
object AccessibilityAdbHelper {

    suspend fun ativarAcessibilidadeViaAdb(adbExecutor: AdbShellExecutor): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val componentName = "com.fofinhos.autosplit/com.fofinhos.autosplit.TouchCaptureAccessibilityService"

                // Limpa configurações antigas e define o nosso serviço
                adbExecutor.executarSync("settings put secure enabled_accessibility_services $componentName")
                // Ativa a chave master do sistema
                adbExecutor.executarSync("settings put secure accessibility_enabled 1")

                Log.d("AutoSplit", "Acessibilidade ativada via ADB.")
                true
            } catch (e: Exception) {
                Log.e("AutoSplit", "Erro ao ativar acessibilidade via ADB", e)
                false
            }
        }
    }

    suspend fun desativarAcessibilidadeViaAdb(adbExecutor: AdbShellExecutor): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Remove o serviço da lista
                adbExecutor.executarSync("settings put secure enabled_accessibility_services null")
                // Desliga a chave master (opcional, mas garante que não ficará nada rodando em background)
                adbExecutor.executarSync("settings put secure accessibility_enabled 0")

                Log.d("AutoSplit", "Acessibilidade desativada via ADB.")
                true
            } catch (e: Exception) {
                Log.e("AutoSplit", "Erro ao desativar acessibilidade", e)
                false
            }
        }
    }
}