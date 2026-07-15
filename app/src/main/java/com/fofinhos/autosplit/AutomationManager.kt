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
 * Gerencia a automação inteligente em split-screen.
 * Identifica se os aplicativos já estão abertos em segundo plano para preservar o seu estado.
 * Só inicializa a GhostActivity se for estritamente necessário (App 1 em background).
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

                Log.d(TAG, "Iniciando fluxo inteligente de automação. CarMode: $carModeEnabled")

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

                // Constante para evitar que o Kotlin interpole variáveis de ambiente do Shell do Android
                val d = "$"

                // =========================================================================
// 1. Abre o Primeiro App limpando a Task anterior mas mantendo o processo
// =========================================================================
                Log.d(TAG, "Ativando App 1 com Reset de Task: $app1")

// Adicionada a flag -f 0x10008000 para limpar o estado fullscreen anterior
                adbExecutor.executarSync("am start --user 0 -f 0x10008000 --windowingMode 3 -n \$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $app1 | tail -n 1)")

// Espera o sistema criar o dock e estabilizar a metade do ecrã
                delay(2000)

// =========================================================================
// 2. Abre o Segundo App limpando a Task anterior
// =========================================================================
                Log.d(TAG, "Ativando App 2 com Reset de Task: $app2")

// Adicionada a flag -f 0x10008000 também no segundo app
                adbExecutor.executarSync("am start --user 0 -f 0x10008000 -n \$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $app2 | tail -n 1) --activity-brought-to-front")

                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Sequência de Automação Concluída!", Toast.LENGTH_SHORT).show()
                }

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