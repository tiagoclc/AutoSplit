package com.fofinhos.autosplit

import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.content.BroadcastReceiver
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import android.content.IntentFilter
import android.widget.EditText
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

    // Coordenadas Modo Paisagem (Landscape)
    private lateinit var editTapXLand: EditText
    private lateinit var editTapYLand: EditText
    private lateinit var btnRestoreDolphinLand: MaterialButton
    private lateinit var btnCaptureClickLand: MaterialButton

    // Coordenadas Modo Retrato (Portrait)
    private lateinit var editTapXPort: EditText
    private lateinit var editTapYPort: EditText
    private lateinit var btnRestoreDolphinPort: MaterialButton
    private lateinit var btnCaptureClickPort: MaterialButton

    private lateinit var switchAutoStart: MaterialSwitch
    private lateinit var switchCarMode: MaterialSwitch
    private lateinit var txtSystemStatus: TextView

    // Grava dinamicamente na pasta correta baseando-se na rotação atual do ecrã durante a captura
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val x = intent?.getIntExtra("x", -1) ?: -1
            val y = intent?.getIntExtra("y", -1) ?: -1
            if (x != -1 && y != -1) {
                val orientation = resources.configuration.orientation
                val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)

                if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    editTapXLand.setText(x.toString())
                    editTapYLand.setText(y.toString())
                    prefs.edit().putInt("tap_x_land", x).putInt("tap_y_land", y).apply()
                } else {
                    editTapXPort.setText(x.toString())
                    editTapYPort.setText(y.toString())
                    prefs.edit().putInt("tap_x_port", x).putInt("tap_y_port", y).apply()
                }

                if (::btnCaptureClickLand.isInitialized) btnCaptureClickLand.text = "Capturar"
                if (::btnCaptureClickPort.isInitialized) btnCaptureClickPort.text = "Capturar"

                Toast.makeText(this@MainActivity, "Gravado com sucesso! X=$x, Y=$y", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val countdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val seconds = intent?.getIntExtra("seconds", 0) ?: 0
            val text = if (seconds > 0) "Capturando... ($seconds)" else "Capturar"

            if (::btnCaptureClickLand.isInitialized) btnCaptureClickLand.text = text
            if (::btnCaptureClickPort.isInitialized) btnCaptureClickPort.text = text
        }
    }

    private var selectedApp1: AppConfigData? = null
    private var selectedApp2: AppConfigData? = null
    private val appList = ArrayList<AppConfigData>()

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    data class AppConfigData(val packageName: String, val label: String, val icon: Drawable) {
        override fun toString(): String = label
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)
        val savedApp1 = prefs.getString("app1", null)
        val savedApp2 = prefs.getString("app2", null)

        val savedTapXLand = prefs.getInt("tap_x_land", -1)
        val savedTapYLand = prefs.getInt("tap_y_land", -1)
        val savedTapXPort = prefs.getInt("tap_x_port", -1)
        val savedTapYPort = prefs.getInt("tap_y_port", -1)

        val bootAutoStart = prefs.getBoolean("boot_auto_start", true)
        val forcarConfiguracoes = intent.getBooleanExtra("FORCE_SETTINGS", false)
        val isFromBoot = intent.getBooleanExtra("AUTO_START", false)

        // Valida se todos os parâmetros cruciais de ambos os modos existem para rodar direto
        val deveExecutarDireto = !forcarConfiguracoes &&
                savedApp1 != null && savedApp2 != null &&
                savedTapXLand != -1 && savedTapYLand != -1 &&
                savedTapXPort != -1 && savedTapYPort != -1 &&
                (!isFromBoot || bootAutoStart)

        if (deveExecutarDireto) {
            super.onCreate(savedInstanceState)
            moveTaskToBack(true)

            lifecycleScope.launch {
                delay(300)
                dispararEFecharAplicativo(savedApp1!!, savedApp2!!)
            }
            return
        }

        setTheme(R.style.Theme_AutoSplit)
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(intent)
        }

        carregarAplicativosDoSistema()
        setContentView(R.layout.activity_main)
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
                // Atualiza os ícones dos apps já selecionados caso tenham carregado agora
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
                status.append(if (adbAtivo) "• ADB Loopback Ativo\n" else "• ADB Desconectado (Ative Wireless Debugging)\n")
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

        // Mapeamento dos novos IDs do Layout inflado
        editTapXLand = findViewById(R.id.edit_tap_x_land)
        editTapYLand = findViewById(R.id.edit_tap_y_land)
        btnRestoreDolphinLand = findViewById(R.id.btn_restore_dolphin_land)
        btnCaptureClickLand = findViewById(R.id.btn_capture_click_land)

        editTapXPort = findViewById(R.id.edit_tap_x_port)
        editTapYPort = findViewById(R.id.edit_tap_y_port)
        btnRestoreDolphinPort = findViewById(R.id.btn_restore_dolphin_port)
        btnCaptureClickPort = findViewById(R.id.btn_capture_click_port)

        switchAutoStart = findViewById(R.id.switch_auto_start)
        switchCarMode = findViewById(R.id.switch_car_mode)

        val prefs = getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)

        // Inicializa os inputs com os valores salvos ou padrões corretos de fábrica do carro
        editTapXLand.setText(prefs.getInt("tap_x_land", 1712).toString())
        editTapYLand.setText(prefs.getInt("tap_y_land", 1044).toString())
        editTapXPort.setText(prefs.getInt("tap_x_port", 1044).toString())
        editTapYPort.setText(prefs.getInt("tap_y_port", 1712).toString())

        switchAutoStart.isChecked = prefs.getBoolean("boot_auto_start", true)
        switchCarMode.isChecked = prefs.getBoolean("car_mode_enabled", true)

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("boot_auto_start", isChecked).apply()
            toggleBootReceiver(isChecked)
        }

        switchCarMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("car_mode_enabled", isChecked).apply()
        }

        // Resets individuais para cada orientação física do display
        btnRestoreDolphinLand.setOnClickListener {
            editTapXLand.setText("1712")
            editTapYLand.setText("1044")
            prefs.edit().putInt("tap_x_land", 1712).putInt("tap_y_land", 1044).apply()
            Toast.makeText(this, "Padrão Paisagem Salvo!", Toast.LENGTH_SHORT).show()
        }

        btnRestoreDolphinPort.setOnClickListener {
            editTapXPort.setText("1044")
            editTapYPort.setText("1712")
            prefs.edit().putInt("tap_x_port", 1044).putInt("tap_y_port", 1712).apply()
            Toast.makeText(this, "Padrão Retrato Salvo!", Toast.LENGTH_SHORT).show()
        }

        val dectectorClickAction = View.OnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                AccessibilityAdbHelper.ativarAcessibilidadeViaAdb(AdbShellExecutor(this@MainActivity))
            }
        }
        btnCaptureClickLand.setOnClickListener(dectectorClickAction)
        btnCaptureClickPort.setOnClickListener(dectectorClickAction)

        savedApp1?.let { pkg ->
            appList.find { it.packageName == pkg }?.let { app ->
                selectedApp1 = app
                imgApp1.setImageDrawable(app.icon)
                txtApp1.text = app.label
            } ?: run {
                imgApp1.setImageResource(R.drawable.ic_question_mark)
                txtApp1.text = "App 1 (Esquerda)"
            }
        } ?: run {
            imgApp1.setImageResource(R.drawable.ic_question_mark)
            txtApp1.text = "App 1 (Esquerda)"
        }

        savedApp2?.let { pkg ->
            appList.find { it.packageName == pkg }?.let { app ->
                selectedApp2 = app
                imgApp2.setImageDrawable(app.icon)
                txtApp2.text = app.label
            } ?: run {
                imgApp2.setImageResource(R.drawable.ic_question_mark)
                txtApp2.text = "App 2 (Direita)"
            }
        } ?: run {
            imgApp2.setImageResource(R.drawable.ic_question_mark)
            txtApp2.text = "App 2 (Direita)"
        }

        btnSelect1.setOnClickListener { mostrarDialogoSelecao(true) }
        btnSelect2.setOnClickListener { mostrarDialogoSelecao(false) }

        btnRun.setOnClickListener {
            val app1 = selectedApp1?.packageName ?: ""
            val app2 = selectedApp2?.packageName ?: ""
            val xLandStr = editTapXLand.text.toString().trim()
            val yLandStr = editTapYLand.text.toString().trim()
            val xPortStr = editTapXPort.text.toString().trim()
            val yPortStr = editTapYPort.text.toString().trim()

            if (app1.isEmpty() || app2.isEmpty() ||
                xLandStr.isEmpty() || yLandStr.isEmpty() ||
                xPortStr.isEmpty() || yPortStr.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Configuração Incompleta")
                    .setMessage("Por favor, selecione os aplicativos e garanta que todas as coordenadas estejam preenchidas.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            val tapXLand = xLandStr.toIntOrNull() ?: 1712
            val tapYLand = yLandStr.toIntOrNull() ?: 1044
            val tapXPort = xPortStr.toIntOrNull() ?: 1044
            val tapYPort = yPortStr.toIntOrNull() ?: 1712

            getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE).edit().apply {
                putString("app1", app1)
                putString("app2", app2)
                putInt("tap_x_land", tapXLand)
                putInt("tap_y_land", tapYLand)
                putInt("tap_x_port", tapXPort)
                putInt("tap_y_port", tapYPort)
                apply()
            }
            // CORREÇÃO: Passando apenas os 3 parâmetros válidos exigidos pela assinatura de rodarFluxo
            dispararEFecharAplicativo(app1, app2)
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

    // CORREÇÃO: Removidos parâmetros redundantes de inteiros da assinatura local
    private fun dispararEFecharAplicativo(app1: String, app2: String) {
        AutomationManager.rodarFluxo(this, app1, app2)
        startService(Intent(this, FloatingService::class.java))
        finish()
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureReceiver, IntentFilter("com.fofinhos.autosplit.COORDINATES_CAPTURED"), RECEIVER_NOT_EXPORTED)
            registerReceiver(countdownReceiver, IntentFilter("com.fofinhos.autosplit.CAPTURE_COUNTDOWN"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(captureReceiver, IntentFilter("com.fofinhos.autosplit.COORDINATES_CAPTURED"))
            registerReceiver(countdownReceiver, IntentFilter("com.fofinhos.autosplit.CAPTURE_COUNTDOWN"))
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(captureReceiver)
        unregisterReceiver(countdownReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusUpdateJob?.cancel()
        AdbShellExecutor(this).fecharConexao()
    }
}