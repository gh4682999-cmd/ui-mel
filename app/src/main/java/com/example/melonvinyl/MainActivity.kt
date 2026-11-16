package com.example.melonvinyl

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    // 비워 두면 앱에 포함된 화면(assets/index.html)을 사용합니다. 서버 없이 단독 실행 가능.
    // 차트 TOP100 서버(melon-vinyl)를 쓰려면 "http://192.168.0.12:3000" 처럼 PC IP를 넣으세요.
    private val serverUrl = ""
    private val melonPkg = "com.iloen.melon"

    private lateinit var web: WebView
    private var controller: MediaController? = null
    private var listening = false
    private var asked = false

    private val controllerCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() { controller = null }
    }
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { attach(it) }

    /** 웹 화면의 버튼 -> 멜론으로 명령 전달 */
    inner class Bridge {
        @JavascriptInterface
        fun cmd(c: String) {
            runOnUiThread {
                val t = controller?.transportControls ?: return@runOnUiThread
                when (c) {
                    "play" -> t.play()
                    "pause" -> t.pause()
                    "next" -> t.skipToNext()
                    "prev" -> t.skipToPrevious()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String) = push()
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame) {
                        v.loadData(
                            "<body style='font-family:sans-serif;padding:24px'><h3>서버에 연결할 수 없습니다</h3>" +
                            "<p>PC에서 <code>npm start</code>가 실행 중인지, MainActivity의 serverUrl이 맞는지 확인하세요.</p>" +
                            "<p>$serverUrl</p></body>", "text/html", "utf-8")
                    }
                }
            }
            loadUrl(if (serverUrl.isEmpty()) "file:///android_asset/index.html" else serverUrl)
        }
        setContentView(web)

        // 차량 화면용: 상태바·내비게이션바를 숨기는 전체화면(스와이프하면 잠깐 표시)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, web).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        startSync()
    }

    override fun onDestroy() {
        controller?.unregisterCallback(controllerCb)
        if (listening) {
            getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsListener)
        }
        super.onDestroy()
    }

    private fun startSync() {
        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            if (!asked) {
                asked = true
                AlertDialog.Builder(this)
                    .setTitle("멜론 연동")
                    .setMessage("멜론의 재생 정보를 받아오려면 '알림 접근' 권한이 필요합니다. 설정에서 '멜론 턴테이블'을 켜 주세요. 알림 내용은 읽지 않고, 재생 중인 곡 정보만 사용합니다.")
                    .setPositiveButton("설정 열기") { _, _ ->
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    .setNegativeButton("나중에", null)
                    .show()
            }
            return
        }
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val cn = ComponentName(this, NowPlayingListener::class.java)
            if (!listening) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, cn)
                listening = true
            }
            attach(msm.getActiveSessions(cn))
        } catch (e: SecurityException) {
            // 권한이 아직 반영되지 않음 - 다음 onResume에서 재시도
        }
    }

    private fun attach(list: List<MediaController>?) {
        val next = list?.firstOrNull { it.packageName == melonPkg }
        if (next?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(controllerCb)
        controller = next
        next?.registerCallback(controllerCb)
        push()
    }

    /** 멜론의 현재 곡/재생 상태를 웹 화면(window.onNowPlaying)으로 전달 */
    private fun push() {
        val c = controller ?: return
        val m = c.metadata ?: return
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: 0L
        if (playing && st != null) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val o = JSONObject()
            .put("title", title)
            .put("artist", m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            .put("duration", m.getLong(MediaMetadata.METADATA_KEY_DURATION))
            .put("position", pos)
            .put("playing", playing)
            .put("art", bmp?.let { toDataUri(it) } ?: "")
        web.evaluateJavascript(
            "window.onNowPlaying&&window.onNowPlaying(${JSONObject.quote(o.toString())})", null)
    }

    private fun toDataUri(src: Bitmap): String {
        val scale = 400f / maxOf(src.width, src.height)
        val b = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
