package com.fofinhos.autosplit

import android.content.Context
import android.content.Intent
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

                val adbExecutor = AdbShellExecutor(appContext)

                Log.d(TAG, "Iniciando fluxo inteligente de automação.")

                // Se o seletor do botão flutuante está ativado, garante que o botão apareça
                val prefs = appContext.getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)
                val floatingEnabled = prefs.getBoolean("floating_button_enabled", false)
                if (floatingEnabled && !FloatingService.isRunning) {
                    withContext(Dispatchers.Main) {
                        appContext.startService(Intent(appContext, FloatingService::class.java))
                    }
                }

                // Constante para evitar que o Kotlin interpole variáveis de ambiente do Shell do Android
                val d = "$"

                // =========================================================================
                // 1. GERIR O APP 1 (Lado Primário - Stack 3)
                // =========================================================================
                Log.d(TAG, "Processando App 1: $app1")

                // Este script em lote faz toda a tomada de decisão no terminal do Android:
                // - Se o App 1 está aberto: abre a GhostActivity, espera 1s (sleep) e move o App 1.
                // - Se o App 1 está fechado: abre-o diretamente em split-screen (com windowingMode 3).
                val cmdApp1 = """
                    TASK_ID=${d}(dumpsys activity activities | awk '/TaskRecord|Task\{/{t=${d}0} index(${d}0, "$app1") > 0 {print t; exit}' | grep -oE "#[0-9]+" | head -n 1 | tr -d '#');
                    if [ ! -z "${d}TASK_ID" ]; then
                        log -t AutoSplitManager "App1 em segundo plano (Task: ${d}TASK_ID). Inicializando GhostActivity para criar a Stack...";
                        am start --user 0 --windowingMode 3 -n com.fofinhos.autosplit/.GhostActivity;
                        sleep 1;
                        STACK_ID=${d}(am stack list | awk '/Stack id=/ {split(${d}2, a, "="); id=a[2]} /mWindowingMode=split-screen-primary/ {print id; exit}');
                        log -t AutoSplitManager "Movendo App1 para stack do split primary...";     
                        am stack move-task ${d}TASK_ID ${d}STACK_ID true;
                    else
                        log -t AutoSplitManager "App1 fechado. Iniciando diretamente no modo split-screen...";
                        am start --user 0 --windowingMode 3 -n ${d}(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $app1 | tail -n 1);
                    fi
                """.trimIndent().replace("\n", " ")

                adbExecutor.executarSync(cmdApp1)

                // Tempo para o sistema processar a criação da janela e estabilizar o lado esquerdo
                delay(1500)

                // =========================================================================
                // 2. GERIR O APP 2 (Lado Secundário - Stack 4)
                // =========================================================================
                Log.d(TAG, "Processando App 2: $app2")


//                val cmdApp2 = """
//                    TASK_ID=${d}(dumpsys activity activities | awk '/TaskRecord|Task\{/{t=${d}0} index(${d}0, "$app2") > 0 {print t; exit}' | grep -oE "#[0-9]+" | head -n 1 | tr -d '#');
//                    if [ ! -z "${d}TASK_ID" ]; then
//                        STACK_ID=${d}(am stack list | awk '/Stack id=/ {split(${d}2, a, "="); id=a[2]} /mWindowingMode=split-screen-secondary/ {print id; exit}');
//                        log -t AutoSplitManager "Movendo App2 para stack do split secondary...";
//                        am stack move-task ${d}TASK_ID ${d}STACK_ID true;
//                    else
//                        log -t AutoSplitManager "App2 fechado. Iniciando diretamente no modo split-screen...";
//                        am start --user 0 -n ${d}(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $app2 | tail -n 1) --activity-brought-to-front;
//                    fi
//                """.trimIndent().replace("\n", " ")
//
//                adbExecutor.executarSync(cmdApp2)

                adbExecutor.executarSync("am start --user 0 -n \$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $app2 | tail -n 1) --activity-brought-to-front")


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


}