package com.clipmind.android.reading

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.clipmind.android.ClipMindApp
import com.clipmind.android.MainActivity
import com.clipmind.android.knowledge.KnowledgeRequest
import com.clipmind.android.network.BatchRequestMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class LearningWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = mutex.withLock {
        val container = (applicationContext as ClipMindApp).container
        val preferences = container.learningSettings.state.value
        val cards = container.localCardRepository.observeCards().first()
        val reviews = container.readingRepository.dao.observeReviews().first()
        val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
        val today = ReviewPolicy.day(now,zone).toString()
        val due = ReviewPolicy.due(cards,reviews,preferences.dailyLimit,now,zone)
        ReviewWidget.update(applicationContext,due.size)
        if (preferences.reminders && due.isNotEmpty() && container.learningSettings.marker("last_reminder") != today) {
            val permitted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (permitted) {
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("review","知识复习",NotificationManager.IMPORTANCE_DEFAULT))
                val intent = PendingIntent.getActivity(applicationContext,41,Intent(applicationContext,MainActivity::class.java).putExtra("open_review",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(41,NotificationCompat.Builder(applicationContext,"review").setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("今日知识复习").setContentText("有 ${due.size} 张卡片等待回忆与思考").setContentIntent(intent).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
                container.learningSettings.mark("last_reminder",today)
            }
        }
        if (!LearningTaskGate.tryAcquire()) return@withLock Result.success()
        try {
        val auth = BatchRequestMetadata.authorizationHeader(container.tokenStore.readToken().orEmpty())
        val config = container.settings.captureAiConfiguration()
        fun allowed() = container.settings.aiEnabled.value && config == container.settings.captureAiConfiguration() && auth == BatchRequestMetadata.authorizationHeader(container.tokenStore.readToken().orEmpty())
        if (allowed() && preferences.automaticAnalysis) {
            cards.filter { it.analysis?.let(ReadingContract::valid) != true && container.learningSettings.marker("analysis:${it.id}:${it.contentRevision}") == null }.take(3).forEach { card ->
                if (!allowed() || !container.learningSettings.state.value.automaticAnalysis) return@forEach
                container.learningSettings.mark("analysis:${card.id}:${card.contentRevision}","attempted")
                try { container.readingRepository.generate(container.readingRepository.prepare(card.id,false),auth) { allowed() && container.learningSettings.state.value.automaticAnalysis } }
                catch (c: CancellationException) { throw c }
                catch (_: Exception) { container.readingRepository.saveDocument("task_error","卡片 ${card.id} 自动完整分析未完成；未自动重试，请在详情页手动重试。") }
            }
        }
        if (allowed() && preferences.automaticRelations) {
            val source = cards.firstOrNull { container.learningSettings.marker("relations:${it.id}:${it.contentRevision}") == null }
            if (source != null) {
                container.learningSettings.mark("relations:${source.id}:${source.contentRevision}","attempted")
                try {
                    val plan = container.semanticIndex.prepare(source.id,auth)
                    val matches = container.semanticIndex.buildAndFind(plan,auth) { allowed() && container.learningSettings.state.value.automaticRelations }
                    for (match in matches) {
                        if (!allowed() || !container.learningSettings.state.value.automaticRelations || !container.semanticIndex.current(plan)) break
                        val input = KnowledgeRequest(listOf(plan.cards.first { it.id == source.id.toString() },match.card))
                        val result = container.knowledgeClient.generate(input,config,container.apiKeyStore.readForAuthorization(config.mode.providerId),auth)
                        if (allowed() && container.learningSettings.state.value.automaticRelations) container.knowledgeRepository.save(input,result)
                    }
                } catch (c: CancellationException) { throw c }
                catch (_: Exception) { container.readingRepository.saveDocument("task_error","卡片 ${source.id} 自动语义关联未完成；请检查向量配置并手动重试。") }
            }
        }
        if (allowed() && preferences.weeklyReports) {
            try {
                val plan = weeklyPlan(cards,now,zone,true)
                val id = "weekly:${plan.start}"
                if (container.readingRepository.dao.document(id) == null && container.learningSettings.marker(id) == null) {
                    container.learningSettings.mark(id,"attempted")
                    val text = generateWeek(container.api,auth,plan)
                    container.readingRepository.saveWeek(plan,text) { allowed() && container.learningSettings.state.value.weeklyReports }
                }
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { if (container.learningSettings.marker("weekly_error") != today) { container.readingRepository.saveDocument("task_error","自动周报未完成（来源超限、已变化或服务不可用），请手动生成。 "); container.learningSettings.mark("weekly_error",today) } }
        }
        Result.success()
        } finally { LearningTaskGate.release() }
    }
    companion object {
        private val mutex = Mutex()
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("learning-maintenance",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<LearningWorker>(12,TimeUnit.HOURS).build())
            WorkManager.getInstance(context).enqueueUniqueWork("learning-refresh",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<LearningWorker>().build())
        }
    }
}
