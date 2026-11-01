package com.example.gpsjoy

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var etSpeed: EditText
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun field(root: LinearLayout, label: String, value: String): EditText {
        root.addView(TextView(this).apply { text = label })
        val et = EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
        }
        root.addView(et)
        return et
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(dp(20) + b.left, dp(20) + b.top, dp(20) + b.right, dp(20) + b.bottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = "GPS Joy"
            textSize = 24f
        })
        etSpeed = field(root, "최대 시속 (km/h)", "5.0")
        etLat = field(root, "시작 위도", "37.5665")
        etLon = field(root, "시작 경도", "126.9780")

        root.addView(Button(this).apply {
            text = "시작 위치를 내 실제 위치로"
            setOnClickListener { fillRealLocation() }
        })
        root.addView(Button(this).apply {
            text = "Start (조이스틱 띄우기)"
            setOnClickListener { onStart() }
        })
        root.addView(Button(this).apply {
            text = "Stop (실제 위치로 복귀)"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, JoystickService::class.java)
                        .setAction(JoystickService.ACTION_STOP)
                )
            }
        })
        root.addView(TextView(this).apply {
            text = "\n사전 설정: 개발자 옵션 > 모의 위치 앱 선택 > GPS Joy"
        })
        setContentView(root)
    }

    private fun onStart() {
        // 1) 다른 앱 위에 표시 권한
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시'를 허용해 주세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        // 2) 위치/알림 런타임 권한
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        launchService()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(this, "위치 권한이 필요합니다", Toast.LENGTH_LONG).show()
            return
        }
        if (code == 1) launchService()   // Start 버튼에서 요청한 경우만 시작
        else Toast.makeText(this, "권한 허용됨. 버튼을 한 번 더 눌러주세요", Toast.LENGTH_SHORT).show()
    }

    private fun launchService() {
        val speed = etSpeed.text.toString().replace(",", ".").toDoubleOrNull()
        val lat = etLat.text.toString().toDoubleOrNull()
        val lon = etLon.text.toString().toDoubleOrNull()
        if (speed == null || speed < 0 || lat == null || lon == null ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0
        ) {
            Toast.makeText(this, "입력값을 확인하세요", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(
            Intent(this, JoystickService::class.java)
                .putExtra(JoystickService.EXTRA_SPEED, speed)
                .putExtra(JoystickService.EXTRA_LAT, lat)
                .putExtra(JoystickService.EXTRA_LON, lon)
        )
        Toast.makeText(this, "조이스틱 시작. 다른 앱으로 이동해도 됩니다", Toast.LENGTH_SHORT).show()
    }

    /** 현재 실제 위치를 가져와 위도/경도 칸에 채움 */
    private fun fillRealLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2
            )
            return
        }
        if (JoystickService.running) {
            Toast.makeText(this, "모의 위치 실행 중에는 실제 위치를 못 가져옵니다. 먼저 Stop 하세요", Toast.LENGTH_LONG).show()
            return
        }
        val mgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
        if (loc == null) {
            Toast.makeText(this, "실제 위치를 아직 못 잡았습니다. 지도 앱을 한 번 켜서 위치를 잡은 뒤 다시 눌러주세요", Toast.LENGTH_LONG).show()
            return
        }
        etLat.setText("%.6f".format(loc.latitude))
        etLon.setText("%.6f".format(loc.longitude))
        Toast.makeText(this, "실제 위치를 채웠습니다", Toast.LENGTH_SHORT).show()
    }
}
