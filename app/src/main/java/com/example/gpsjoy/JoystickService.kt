package com.example.gpsjoy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** 포그라운드 서비스: 오버레이 조이스틱 + 모의 위치 전송 */
class JoystickService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.gpsjoy.STOP"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        private const val CHANNEL_ID = "gpsjoy"
        private const val EARTH_R = 6371000.0
        private const val TICK_MS = 100L
        private const val DEADZONE = 0.08f
        @Volatile var running = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lm: LocationManager? = null
    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var info: TextView? = null

    private var lat = 37.5665
    private var lon = 126.9780
    private var speedMax = 5.0
    private var bearing = 0.0
    private var ex = 0f
    private var ny = 0f
    private var last = 0L

    private val tick = object : Runnable {
        override fun run() {
            step()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        speedMax = intent?.getDoubleExtra(EXTRA_SPEED, 5.0) ?: 5.0
        lat = intent?.getDoubleExtra(EXTRA_LAT, lat) ?: lat
        lon = intent?.getDoubleExtra(EXTRA_LON, lon) ?: lon

        try {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Toast.makeText(this, "서비스 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = mgr
        try {
            try { mgr.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
            mgr.addTestProvider(
                LocationManager.GPS_PROVIDER, false, false, false, false,
                true, true, true,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE
            )
            mgr.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "모의 위치 앱으로 지정되지 않았습니다.\n개발자 옵션 > 모의 위치 앱 선택에서 GPS Joy를 지정하세요.",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            addOverlay()
        } catch (e: Exception) {
            Toast.makeText(this, "오버레이 실패: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        last = SystemClock.elapsedRealtime()
        handler.post(tick)
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GPS Joy", NotificationManager.IMPORTANCE_LOW)
