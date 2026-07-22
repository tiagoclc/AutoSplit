package com.fofinhos.autosplit

import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var imgApp1: ImageView
    private lateinit var imgApp2: ImageView
    private lateinit var txtApp1: TextView
    private lateinit var txtApp2: TextView
    private lateinit var btnSelect1: MaterialButton
    private lateinit var btnSelect2: MaterialButton
    private lateinit var btnRun: MaterialButton
    
    private lateinit var switchAutoStart: MaterialSwitch
    private lateinit var switchFloatingButton: MaterialSwitch
    private lateinit var txtSystemStatus: TextView

    private var selectedApp1: AppConfigData? = null
    private var selectedApp2: AppConfigData? = null
    private val appList = ArrayList<AppConfigData>()

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    data class AppConfigData(val packageName: String, val label: String, val icon: Drawable) {
        override fun toString(): String = label
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setTheme(R.style.Theme_AutoSplit)
        setContentView(R.layout.activity_main)

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(intent)
        }

        val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)
        val savedApp1 = prefs.getString("app1", null)
        val savedApp2 = prefs.getString("app2", null)

        carregarAplicativosDoSistema()
        inicializarComponentesVisual(savedApp1, savedApp2)
        atualizarStatusSistemaDinamicamente()
    }

    private fun carregarAplicativosDoSistema() {
        lifecycleScope.launch(Dispatchers.Default) {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

            val tempAppList = mutableListOf<AppConfigData>()
            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName) continue
                val label = info.loadLabel(pm).toString()
                val icon = info.loadIcon(pm)
                tempAppList.add(AppConfigData(pkg, label, icon))
            }
            tempAppList.sortBy { it.label.lowercase() }

            withContext(Dispatchers.Main) {
                appList.clear()
                appList.addAll(tempAppList)
                reaplicarSelecoes()
            }
        }
    }

    private fun reaplicarSelecoes() {
        val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)
        val savedApp1 = prefs.getString("app1", null)
        val savedApp2 = prefs.getString("app2", null)
        
        savedApp1?.let { pkg ->
            appList.find { it.packageName == pkg }?.let { app ->
                selectedApp1 = app
                imgApp1.setImageDrawable(app.icon)
                txtApp1.text = app.label
            }
        }
        savedApp2?.let { pkg ->
            appList.find { it.packageName == pkg }?.let { app ->
                selectedApp2 = app
                imgApp2.setImageDrawable(app.icon)
                txtApp2.text = app.label
            }
        }
    }

    private var statusUpdateJob: Job? = null
    private fun atualizarStatusSistemaDinamicamente() {
        txtSystemStatus = findViewById(R.id.txt_system_status)
        statusUpdateJob?.cancel()
        statusUpdateJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val status = StringBuilder()
                val adbAtivo = try {
                    java.net.Socket("127.0.0.1", 5555).use { true }
                } catch (e: Exception) {
                    false
                }
                status.append(if (adbAtivo) "• ADB Loopback Ativo\n" else "• ADB Desconectado\n")
                val overlayOk = Settings.canDrawOverlays(this@MainActivity)
                status.append(if (overlayOk) "• Overlay Autorizado\n" else "• Overlay Pendente\n")
                status.append("• Monitoramento de Boot Ativo\n")
                val serviceRunning = isServiceRunning(FloatingService::class.java)
                status.append(if (serviceRunning) "• Painel de Controle Ativo" else "• Painel de Controle Inativo")

                withContext(Dispatchers.Main) {
                    txtSystemStatus.text = status.toString()
                }
                delay(3000)
            }
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) return true
        }
        return false
    }

    private fun inicializarComponentesVisual(savedApp1: String?, savedApp2: String?) {
        imgApp1 = findViewById(R.id.img_app1_icon)
        imgApp2 = findViewById(R.id.img_app2_icon)
        txtApp1 = findViewById(R.id.txt_app1_label)
        txtApp2 = findViewById(R.id.txt_app2_label)
        btnSelect1 = findViewById(R.id.btn_select_app1)
        btnSelect2 = findViewById(R.id.btn_select_app2)
        btnRun = findViewById(R.id.btn_run_automation)

        switchAutoStart = findViewById(R.id.switch_auto_start)
        switchFloatingButton = findViewById(R.id.switch_floating_button)

        val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)
        switchAutoStart.isChecked = prefs.getBoolean("boot_auto_start", true)
        switchFloatingButton.isChecked = prefs.getBoolean("floating_button_enabled", false)

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("boot_auto_start", isChecked).apply()
            toggleBootReceiver(isChecked)
        }

        switchFloatingButton.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("floating_button_enabled", isChecked).apply()
            if (isChecked) {
                if (Settings.canDrawOverlays(this)) {
                    startService(Intent(this, FloatingService::class.java))
                } else {
                    switchFloatingButton.isChecked = false
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    overlayPermissionLauncher.launch(intent)
                }
            } else {
                stopService(Intent(this, FloatingService::class.java))
            }
        }

        btnSelect1.setOnClickListener { mostrarDialogoSelecao(true) }
        btnSelect2.setOnClickListener { mostrarDialogoSelecao(false) }

        btnRun.setOnClickListener {
            val app1 = selectedApp1?.packageName ?: ""
            val app2 = selectedApp2?.packageName ?: ""
            if (app1.isEmpty() || app2.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Configuração Incompleta")
                    .setMessage("Selecione os dois apps")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE).edit().apply {
                putString("app1", app1)
                putString("app2", app2)
                apply()
            }
            AutomationManager.rodarFluxo(this, app1, app2)
            finish()
        }
    }

    private fun mostrarDialogoSelecao(isApp1: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_list, null)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recycler_app_list)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = AppListAdapter(appList) { app ->
            if (isApp1) {
                selectedApp1 = app
                imgApp1.setImageDrawable(app.icon)
                txtApp1.text = app.label
            } else {
                selectedApp2 = app
                imgApp2.setImageDrawable(app.icon)
                txtApp2.text = app.label
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun toggleBootReceiver(enabled: Boolean) {
        val pm = packageManager
        val receiver = android.content.ComponentName(this, BootReceiver::class.java)
        val state = if (enabled) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        pm.setComponentEnabledSetting(receiver, state, android.content.pm.PackageManager.DONT_KILL_APP)
    }

    override fun onResume() {
        super.onResume()
        // Estado do switch agora é persistente e não depende apenas se o serviço está rodando no momento
    }

    override fun onDestroy() {
        super.onDestroy()
        statusUpdateJob?.cancel()
        AdbShellExecutor(this).fecharConexao()
    }
}
