package com.clipmind.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.clipmind.android.ui.ClipMindAppRoot
import com.clipmind.android.ui.ClipMindTheme
import com.clipmind.android.ui.MainViewModel
import com.clipmind.android.export.ExportFormat
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(viewModel::addManualText)
    }
    private val zipDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { uri -> viewModel.export(uri, ExportFormat.OBSIDIAN_ZIP) } }
    private val csvDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { uri -> viewModel.export(uri, ExportFormat.CSV) } }
    private val textDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let { uri -> viewModel.export(uri, ExportFormat.PLAIN_TEXT) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptSharedText(intent)
        setContent {
            val state by viewModel.uiState.collectAsState()
            ClipMindTheme {
                ClipMindAppRoot(
                    state = state,
                    vm = viewModel,
                    onStartCapture = ::startCapture,
                    onVoiceInput = ::startVoiceInput,
                    onOpenShizuku = ::openShizuku,
                    onCopy = ::copyText,
                    onExport = ::createExportDocument,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptSharedText(intent)
    }

    private fun acceptSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf(String::isNotBlank)?.let(viewModel::addManualText)
        }
    }

    private fun startCapture() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        viewModel.startCapture()
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要保存的卡片内容")
        }
        if (intent.resolveActivity(packageManager) != null) speech.launch(intent)
        else viewModel.showMessage("设备没有可用的系统语音识别服务")
    }

    private fun openShizuku() {
        packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let(::startActivity)
            ?: startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS))
    }

    private fun createExportDocument(format: ExportFormat) {
        val base = "ClipMind-${LocalDate.now()}"
        when (format) {
            ExportFormat.OBSIDIAN_ZIP -> zipDocument.launch("$base-Obsidian.zip")
            ExportFormat.CSV -> csvDocument.launch("$base.csv")
            ExportFormat.PLAIN_TEXT -> textDocument.launch("$base.txt")
        }
    }

    private fun copyText(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("ClipMind 卡片", text))
    }
}
