package com.fofinhos.autosplit

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gerencia a automação simples: abre apps e executa comandos de toque (TAP).
 */
object AutomationManager {

    private const val TAG = "AutoSplitManager"

    fun rodarFluxo(context: Context, app1: String, app2: String, tapX: Int = 1712, tapY: Int = 1044) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Se não houver apps selecionados, não faz nada
                if (app1.isEmpty() || app2.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Selecione os apps primeiro", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val adbExecutor = AdbShellExecutor(context)

                Log.d(TAG, "Iniciando fluxo simples de automação com TAP ($tapX, $tapY)")

                // 1. Abre ou traz à tona o Primeiro App
                Log.d(TAG, "Ativando App 1: $app1")
                // A flag --activity-brought-to-front do 'am start' combinada com flags de intent 
                // garante que o app seja trazido para frente se já estiver em background.
                adbExecutor.executarSync("am start -n $(cmd package resolve-activity --brief $app1 | tail -n 1) -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --activity-brought-to-front")
                
                // Espera o app carregar ou estabilizar na frente
                delay(3000)

                // 2. Executa o toque (TAP) via ADB para acionar o Split Screen no sistema
                Log.d(TAG, "Executando TAP via ADB para Split Screen em $tapX, $tapY")
                adbExecutor.executarSync("input tap $tapX $tapY")
                
                // Espera a animação do sistema
                delay(2000)

                // 3. Abre ou traz à tona o Segundo App
                Log.d(TAG, "Ativando App 2: $app2")
                adbExecutor.executarSync("am start -n $(cmd package resolve-activity --brief $app2 | tail -n 1) -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --activity-brought-to-front")

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Sequência de Automação Concluída!", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Throwable) {
                Log.e(TAG, "Erro na automação: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun limparDisplays(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adbExecutor = AdbShellExecutor(context)
                adbExecutor.executarSync("input keyevent 3") // Home
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao limpar", e)
            }
        }
    }
}
