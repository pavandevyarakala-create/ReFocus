package com.reeltrak.app.services.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.reeltrak.app.MainActivity
import com.reeltrak.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReelNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val notificationManager = NotificationManagerCompat.from(context)

    companion object {
        const val CHANNEL_ID_LIMITS = "channel_limits"
        const val CHANNEL_ID_STREAKS = "channel_streaks"
        const val CHANNEL_ID_SUMMARY = "channel_summary"

        const val NOTIFICATION_ID_LIMIT_WARNING = 1001
        const val NOTIFICATION_ID_LIMIT_REACHED = 1002
        const val NOTIFICATION_ID_STREAK = 1003
        const val NOTIFICATION_ID_SUMMARY = 1004
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val limitsChannel = NotificationChannel(
                CHANNEL_ID_LIMITS,
                "Limit Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when you reach or approach your daily reel limit"
                enableVibration(true)
                enableLights(true)
            }

            val streaksChannel = NotificationChannel(
                CHANNEL_ID_STREAKS,
                "Streaks & Milestones",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for streak milestones and badges"
            }

            val summaryChannel = NotificationChannel(
                CHANNEL_ID_SUMMARY,
                "Daily Summaries",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Morning recaps of your progress"
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(limitsChannel)
            manager.createNotificationChannel(streaksChannel)
            manager.createNotificationChannel(summaryChannel)
            Log.d("ReelNotificationManager", "Notification channels created")
        }
    }

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context,
            1, // Unique request code
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showProgressMilestoneNotification(percentage: Int, reelsRemaining: Int) {
        val title: String
        val message: String

        when (percentage) {
            25 -> {
                title = "Great Start! 🚀"
                message = "You're at 25% of your daily limit. You still have plenty of reels left."
            }
            50 -> {
                title = "Halfway There! ⚖️"
                message = "You've reached 50% of your limit. Stay mindful!"
            }
            75 -> {
                title = "Getting Close! ⚠️"
                message = "You're at 75%. Only $reelsRemaining reels left before you hit your limit."
            }
            90 -> {
                title = "Almost at the Limit! 🚨"
                message = "Watch out! You only have $reelsRemaining reels left to protect your streak."
            }
            else -> {
                title = "Progress Update"
                message = "You are at $percentage% of your daily limit."
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_LIMITS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(if (percentage >= 75) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(getPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        try {
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(NOTIFICATION_ID_LIMIT_WARNING, notification)
                Log.d("ReelNotificationManager", "showProgressMilestoneNotification dispatched successfully ($percentage%)")
            } else {
                Log.e("ReelNotificationManager", "showProgressMilestoneNotification failed: Notifications disabled")
            }
        } catch (e: SecurityException) {
            Log.e("ReelNotificationManager", "showProgressMilestoneNotification failed: SecurityException", e)
        }
    }

    fun showLimitReachedNotification() {
        val title = "Daily Limit Reached!"
        val message = "Stop scrolling now to keep your streak alive!"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_LIMITS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(getPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        try {
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(NOTIFICATION_ID_LIMIT_REACHED, notification)
                Log.d("ReelNotificationManager", "showLimitReachedNotification dispatched successfully")
            } else {
                Log.e("ReelNotificationManager", "showLimitReachedNotification failed: Notifications disabled")
            }
        } catch (e: SecurityException) {
            Log.e("ReelNotificationManager", "showLimitReachedNotification failed: SecurityException", e)
        }
    }

    fun showStreakMilestoneNotification(milestone: Int) {
        val title = "Milestone Unlocked! 🎉"
        val message = "You've reached a ${milestone}-day streak. Keep up the great work!"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_STREAKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(getPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .build()

        try {
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(NOTIFICATION_ID_STREAK, notification)
                Log.d("ReelNotificationManager", "showStreakMilestoneNotification dispatched successfully")
            } else {
                Log.e("ReelNotificationManager", "showStreakMilestoneNotification failed: Notifications disabled")
            }
        } catch (e: SecurityException) {
            Log.e("ReelNotificationManager", "showStreakMilestoneNotification failed: SecurityException", e)
        }
    }

    fun showDailySummaryNotification(savedStreak: Boolean) {
        val title = if (savedStreak) "Streak Protected! 🔥" else "A New Day ☀️"
        val message = if (savedStreak) {
            "You stayed under your limit yesterday! Keep it up."
        } else {
            "You went over your limit yesterday. Today is a fresh start to build a new streak!"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SUMMARY)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(getPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()

        try {
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(NOTIFICATION_ID_SUMMARY, notification)
                Log.d("ReelNotificationManager", "showDailySummaryNotification dispatched successfully")
            } else {
                Log.e("ReelNotificationManager", "showDailySummaryNotification failed: Notifications disabled")
            }
        } catch (e: SecurityException) {
            Log.e("ReelNotificationManager", "showDailySummaryNotification failed: SecurityException", e)
        }
    }
}
