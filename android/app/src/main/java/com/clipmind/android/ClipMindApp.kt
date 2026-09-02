package com.clipmind.android

import android.app.Application
import com.clipmind.android.data.CaptureRepository
import com.clipmind.android.data.ClipMindDatabase
import com.clipmind.android.data.UserSettings
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.network.CaptureApi
import com.clipmind.android.security.AndroidKeystoreTextCipher
import com.clipmind.android.security.SecureTokenStore
import com.clipmind.android.shizuku.ShizukuController
import com.clipmind.android.worker.UploadScheduler
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
    val textCipher = AndroidKeystoreTextCipher()
    val tokenStore = SecureTokenStore(
        app.getSharedPreferences("secure_tokens", Application.MODE_PRIVATE),
        AndroidKeystoreTextCipher("clipmind_auth_token_v1"),
    ).also { store ->
        if (BuildConfig.DEBUG && !store.configured.value && BuildConfig.DEBUG_AUTH_TOKEN.isNotBlank()) {
            store.saveToken(BuildConfig.DEBUG_AUTH_TOKEN)
        }
    }
    val repository = CaptureRepository(database, LocalSafetyFilter(blockedSources = setOf(
        "com.android.systemui", "com.google.android.apps.authenticator2",
    )), textCipher)
    val api: CaptureApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(OkHttpClient.Builder().build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(CaptureApi::class.java)
}
