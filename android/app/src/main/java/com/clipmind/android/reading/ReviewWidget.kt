package com.clipmind.android.reading

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.clipmind.android.MainActivity
import com.clipmind.android.R

class ReviewWidget: AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { update(context,context.getSharedPreferences("review_widget",Context.MODE_PRIVATE).getInt("due",0)); LearningWorker.schedule(context) }
    companion object {
        fun update(context: Context, count: Int) {
            context.getSharedPreferences("review_widget",Context.MODE_PRIVATE).edit().putInt("due",count).apply()
            val manager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName,R.layout.review_widget)
            views.setTextViewText(R.id.review_count,if (count>0) "$count 张卡片待复习" else "打开今日复习")
            views.setOnClickPendingIntent(R.id.review_count,PendingIntent.getActivity(context,42,Intent(context,MainActivity::class.java).putExtra("open_review",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(manager.getAppWidgetIds(ComponentName(context,ReviewWidget::class.java)),views)
        }
    }
}
