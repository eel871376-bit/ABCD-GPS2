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

        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

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

        addOverlay()
        running = true
        last = SystemClock.elapsedRealtime()
        handler.post(tick)
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GPS Joy", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, JoystickService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "정지", stop
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Joy 실행 중")
            .setContentText("조이스틱으로 이동 중")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val handle = TextView(this).apply {
            text = "≡ 이동"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        val joy = JoystickView(this) { x, y -> ex = x; ny = y }
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
        }

        // 실제 위치로 복귀 버튼: 모의 위치를 끄고 서비스 종료
        val resetBtn = TextView(this).apply {
            text = "📍 실제 위치"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            background = GradientDrawable().apply {
                setColor(0xFFCC3333.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                Toast.makeText(this@JoystickService, "실제 위치로 복귀합니다", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }

        box.addView(handle, LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(joy, LinearLayout.LayoutParams(dp(200), dp(200)))
        box.addView(info, LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(
            resetBtn,
            LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(300)
        }

        // 손잡이를 끌어서 오버레이 위치 이동
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    wm?.updateViewLayout(box, lp)
                }
            }
            true
        }

        overlay = box
        wm?.addView(box, lp)
    }

    private fun step() {
        val mgr = lm ?: return
        val now = SystemClock.elapsedRealtime()
        val dt = (now - last) / 1000.0
        last = now

        val mag = min(1f, hypot(ex, ny))
        var kmh = 0.0
        if (mag > DEADZONE && speedMax > 0) {
            bearing = (Math.toDegrees(atan2(ex.toDouble(), ny.toDouble())) + 360) % 360
            kmh = speedMax * mag
            val dist = kmh / 3.6 * dt
            val p = destination(lat, lon, bearing, dist)
            lat = p.first
            lon = p.second
        }

        try {
            val l = Location(LocationManager.GPS_PROVIDER)
            l.latitude = lat
            l.longitude = lon
            l.altitude = 30.0
            l.accuracy = 3f
            l.bearing = bearing.toFloat()
            l.speed = (kmh / 3.6).toFloat()
            l.time = System.currentTimeMillis()
            l.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            mgr.setTestProviderLocation(LocationManager.GPS_PROVIDER, l)
        } catch (e: Exception) {
            Toast.makeText(this, "위치 전송 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        info?.text = "%.6f, %.6f\n%.0f°  %.1f km/h".format(lat, lon, bearing, kmh)
    }

    /** 방위각/거리만큼 이동한 좌표 (구면 근사) */
    private fun destination(la: Double, lo: Double, brDeg: Double, distM: Double): Pair<Double, Double> {
        val br = Math.toRadians(brDeg)
        val d = distM / EARTH_R
        val la1 = Math.toRadians(la)
        val lo1 = Math.toRadians(lo)
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(br))
        var lo2 = lo1 + atan2(sin(br) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        lo2 = (lo2 + 3 * Math.PI) % (2 * Math.PI) - Math.PI
        return Pair(Math.toDegrees(la2), Math.toDegrees(lo2))
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        overlay = null
        // 테스트 프로바이더 제거 = 실제 GPS로 복귀
        try { lm?.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        super.onDestroy()
    }
}
