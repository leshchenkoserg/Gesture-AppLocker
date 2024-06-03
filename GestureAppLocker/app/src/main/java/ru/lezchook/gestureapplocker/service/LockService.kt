package ru.lezchook.gestureapplocker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import ru.lezchook.gestureapplocker.MainActivity
import ru.lezchook.gestureapplocker.R
import ru.lezchook.gestureapplocker.database.AppDB
import java.util.Calendar
import java.util.concurrent.TimeUnit

class LockService: AccessibilityService() {

    private var database: AppDB? = null
    private var lockedPackages: ArrayList<String> = ArrayList()
    private var lockedAppNames: ArrayList<String> = ArrayList()


    private val CHANNEL_ID = "reminder_channel"
    private val NOTIFICATION_ID = 123


    private lateinit var handler: Handler
    private lateinit var runnable: Runnable

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
        this.serviceInfo = info
        database =
            Room.databaseBuilder(this, AppDB::class.java, "AppLockerDB")
                .allowMainThreadQueries()
                .build()
        val appList = database!!.getAppDao().getAllLockedApp()
        for (apps in appList) {
            lockedPackages.add(apps.packageName)
            lockedAppNames.add(apps.appName)
        }

        createNotificationChannel()
        handler = Handler()
        runnable = Runnable {
            val calendar = Calendar.getInstance()
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            if (dayOfMonth == 1) {
                sendNotification()
                resetAttempts()
            }
            handler.postDelayed(runnable, TimeUnit.DAYS.toMillis(1))
        }
        handler.postDelayed(runnable, 5_000L)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && database != null) {
            try {
                val packageName = event.packageName
                val appName = event.text.getOrNull(1)
                if (lockedPackages.contains(packageName) || lockedAppNames.contains(appName)) {
                    val blockList = database!!.getAppDao().getBlockInfo()
                    if (blockList.isNotEmpty() && blockList[0].blockedFlag == 1) {
                        createPopupWindow()
                        val pm = applicationContext.packageManager
                        val intent = pm.getLaunchIntentForPackage(
                            "ru.lezchook.gestureapplocker")
                        startActivity(intent)
                    }
                }
            } catch (e: IndexOutOfBoundsException) {
                e.printStackTrace()
            }
        }
    }

    override fun onInterrupt() {
        TODO("Not yet implemented")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun createPopupWindow() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.RGB_565
        )
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View.inflate(this, R.layout.activity_lock_screen, null)
        val passwordButton = view.findViewById<Button>(R.id.my_btn_lock)
        passwordButton.setOnClickListener {
            windowManager.removeView(view)
        }
        windowManager.addView(view, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendNotification() {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notificationText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
    }

    private fun resetAttempts() {
        val blockList = database?.getAppDao()?.getBlockInfo()
        if (blockList != null) {
            try {
                blockList[0].attemptCount = 0
                database?.getAppDao()?.insertBlockInfo(blockList[0])
            } catch (e: java.lang.IndexOutOfBoundsException) { }
        }
    }
}