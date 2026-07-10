package com.fofinhos.autosplit

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gerencia a automação simples: abre apps e executa comandos de toque (TAP).
 * Identifica a orientação física da tela em tempo real antes de desferir o clique.
 */
object AutomationManager {

    private const val TAG = "AutoSplitManager"

    fun rodarFluxo(context: Context, app1: String, app2: String) {
        // Protege contra vazamento de memória usando o contexto global da aplicação
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Se não houver apps selecionados, não faz nada
                if (app1.isEmpty() || app2.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Selecione os apps primeiro", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val prefs = appContext.getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)
                val carModeEnabled = prefs.getBoolean("car_mode_enabled", true)
                val adbExecutor = AdbShellExecutor(appContext)

                Log.d(TAG, "Iniciando fluxo simples de automação. CarMode: $carModeEnabled")

                if (carModeEnabled) {
                    // Forçar o sistema a entrar em "Modo Carro" de forma mais robusta via UiModeManager.
                    Log.d(TAG, "Ativando Modo Carro (uimode car yes)...")
                    adbExecutor.executarSync("cmd uimode car yes")

                    // Broadcast do Dock Event para compatibilidade com apps mais antigos
                    adbExecutor.executarSync("am broadcast -a android.intent.action.DOCK_EVENT --ei android.intent.extra.DOCK_STATE 2")

                    delay(500)
                } else {
                    // Garante que o modo carro esteja desligado se o toggle estiver off
                    adbExecutor.executarSync("cmd uimode car no")
                    adbExecutor.executarSync("am broadcast -a android.intent.action.DOCK_EVENT --ei android.intent.extra.DOCK_STATE 0")
                }

                // 1. Abre ou traz à tona o Primeiro App
                Log.d(TAG, "Ativando App 1: $app1")
                val carFlag = if (carModeEnabled) "-c android.intent.category.CAR_DOCK" else ""
                val extraFlags = if (carModeEnabled) "--ei android.intent.extra.DOCK_STATE 2 --ez android.intent.extra.CAR_MODE true" else ""

                // Usamos --windowingMode 1 (Fullscreen) para permitir o Split Screen nativo do DiLink
                // Adicionamos extras de DOCK_STATE e CAR_MODE para tentar forçar a interface do app
                adbExecutor.executarSync("am start -n $(cmd package resolve-activity --brief $app1 | tail -n 1) -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $carFlag $extraFlags --windowingMode 5 --activity-brought-to-front")

                // Espera o app carregar ou estabilizar na frente
                delay(3000)

                // =========================================================================
                // RECONHECIMENTO DINÂMICO DE ORIENTAÇÃO EM TEMPO REAL (Antes do Toque)
                // =========================================================================
                val currentOrientation = appContext.resources.configuration.orientation

                val (tapX, tapY) = if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                    // Se a tela estiver em pé, busca as coordenadas salvas no Card Retrato
                    Pair(prefs.getInt("tap_x_port", 1044), prefs.getInt("tap_y_port", 1712))
                } else {
                    // Se estiver deitada, busca as coordenadas salvas no Card Paisagem
                    Pair(prefs.getInt("tap_x_land", 1712), prefs.getInt("tap_y_land", 1044))
                }

                Log.d(TAG, "Orientação real detectada: ${if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) "Retrato" else "Paisagem"}")
                // =========================================================================

                // 2. Executa o toque (TAP) usando as coordenadas dinâmicas resolvidas acima
                Log.d(TAG, "Executando TAP via ADB para Split Screen em $tapX, $tapY")
                adbExecutor.executarSync("input tap $tapX $tapY")

                // Espera a animação do sistema
                delay(2000)

                // 3. Abre ou traz à tona o Segundo App
                Log.d(TAG, "Ativando App 2: $app2")
                adbExecutor.executarSync("am start -n $(cmd package resolve-activity --brief $app2 | tail -n 1) -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $carFlag $extraFlags --windowingMode 5 --activity-brought-to-front")

                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Sequência de Automação Concluída!", Toast.LENGTH_SHORT).show()
                }

                // Executa a limpeza (reset do modo carro) após a automação terminar
                limparDisplays(appContext)

            } catch (e: Throwable) {
                Log.e(TAG, "Erro na automação: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun limparDisplays(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adbExecutor = AdbShellExecutor(appContext)

                // Desativa o modo carro no sistema
                adbExecutor.executarSync("cmd uimode car no")
                adbExecutor.executarSync("am broadcast -a android.intent.action.DOCK_EVENT --ei android.intent.extra.DOCK_STATE 0")

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao limpar", e)
            }
        }
    }
}