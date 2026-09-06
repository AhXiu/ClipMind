package com.clipmind.android

import android.app.Application
import com.clipmind.android.data.CaptureRepository
import com.clipmind.android.data.CardRepository
import com.clipmind.android.data.ClipMindDatabase
import com.clipmind.android.data.UserSettings
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.network.CaptureApi
import com.clipmind.android.network.FixedProviderClient
import com.clipmind.android.network.HealthChecker
import com.clipmind.android.security.AndroidKeystoreTextCipher
import com.clipmind.android.security.KeystoreApiKeySecretStore
import com.clipmind.android.security.SecureTokenStore
import com.clipmind.android.service.CaptureProcessingDiagnostics
import com.clipmind.android.shizuku.ShizukuController
import com.clipmind.android.worker.UploadScheduler
import com.clipmind.android.worker.WorkManagerImmediateUploadScheduler
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ClipMindApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.shizuku.start()
        UploadScheduler.schedule(this)
    }
}

class AppContainer(app: Application) {
    val database = ClipMindDatabase.create(app)
    val settings = UserSettings(app)
    val shizuku = ShizukuController(app)
    val captureDiagnostics = CaptureProcessingDiagnostics()
    val textCipher = AndroidKeystoreTextCipher()
    val tokenStore = SecureTokenStore(
        app.getSharedPreferences("secure_tokens", Application.MODE_PRIVATE),
        AndroidKeystoreTextCipher("clipmind_auth_token_v1"),
    ).also { store ->
        if (BuildConfig.DEBUG && !store.configured.value && BuildConfig.DEBUG_AUTH_TOKEN.isNotBlank()) {
            store.saveToken(BuildConfig.DEBUG_AUTH_TOKEN)
        }
    }
    val apiKeyStore = KeystoreApiKeySecretStore(
        app.getSharedPreferences("secure_provider_key", Application.MODE_PRIVATE),
        AndroidKeystoreTextCipher("clipmind_byok_api_key_v1"),
    )
    val clientAnalyzer = FixedProviderClient()
    private val immediateUploadScheduler = WorkManagerImmediateUploadScheduler(app)
    val api: CaptureApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(OkHttpClient.Builder().build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(CaptureApi::class.java)
    val repository = CaptureRepository(
        database,
        LocalSafetyFilter(blockedSources = setOf(
            "com.android.systemui", "com.google.android.apps.authenticator2",
        )),
        textCipher,
        immediateUploadScheduler,
        settings,
    )
    val cardRepository = CardRepository(api, database.captureOutboxDao(), tokenStore)
    val healthChecker = HealthChecker(api)
}
