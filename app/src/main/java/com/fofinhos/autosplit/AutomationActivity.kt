package com.fofinhos.autosplit

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast

/**
 * Atividade invisível que serve apenas como um ponto de entrada (ícone na gaveta)
 * para disparar a rotina de automação salva.
 */
class AutomationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)
        val app1 = prefs.getString("app1", "") ?: ""
        val app2 = prefs.getString("app2", "") ?: ""

        if (app1.isNotEmpty() && app2.isNotEmpty()) {
            Toast.makeText(this, "Iniciando AutoSplit...", Toast.LENGTH_SHORT).show()
            AutomationManager.rodarFluxo(this, app1, app2)
        } else {
            Toast.makeText(this, "Configure os apps primeiro na tela de Ajustes", Toast.LENGTH_LONG).show()
        }
        
        finish()
    }
}
