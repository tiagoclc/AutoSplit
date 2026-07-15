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



                // =========================================================================
                // RECONHECIMENTO DINÂMICO DE ORIENTAÇÃO EM TEMPO REAL (Antes do Toque)
                // =========================================================================
//                val currentOrientation = appContext.resources.configuration.orientation
//
//                val (tapX, tapY) = if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
//                    // Se a tela estiver em pé, busca as coordenadas salvas no Card Retrato
//                    Pair(prefs.getInt("tap_x_port", 1044), prefs.getInt("tap_y_port", 1712))
//                } else {
//                    // Se estiver deitada, busca as coordenadas salvas no Card Paisagem
//                    Pair(prefs.getInt("tap_x_land", 1712), prefs.getInt("tap_y_land", 1044))
//                }
//
//                Log.d(TAG, "Orientação real detectada: ${if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) "Retrato" else "Paisagem"}")
//

                // =========================================================================
// 1. Abre o Primeiro App no modo SPLIT_SCREEN_PRIMARY (3)
// =========================================================================
                Log.d(TAG, "Ativando App 1 no lado primário: $app1")

// Resolve o componente exato do Launcher (com a categoria correta)
                val cmdResolve1 = "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $app1 | tail -n 1"

// Executa o am start com windowingMode 3 (SPLIT_SCREEN_PRIMARY)
                adbExecutor.executarSync("am start --user 0 --windowingMode 3 -n $cmdResolve1")

// Espera o sistema criar o dock e estabilizar a metade do ecrã
                delay(2000)

// =========================================================================
// 2. Abre o Segundo App normalmente (ele vai cair no espaço adjacente livre)
// =========================================================================
                Log.d(TAG, "Ativando App 2 no lado adjacente: $app2")

// Resolve o componente exato do segundo Launcher
                val cmdResolve2 = "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $app2 | tail -n 1"

// Abre normalmente (SEM windowingMode). O Android coloca-o automaticamente no lado livre!
                adbExecutor.executarSync("am start --user 0 -n $cmdResolve2 --activity-brought-to-front")

                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Sequência de Automação Concluída!", Toast.LENGTH_SHORT).show()
                }
//                delay(1000)
//
//                // Executa a limpeza (reset do modo carro) após a automação terminar
//                limparDisplays(appContext)

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