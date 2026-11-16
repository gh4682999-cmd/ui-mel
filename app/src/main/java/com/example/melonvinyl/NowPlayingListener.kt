package com.example.melonvinyl

import android.service.notification.NotificationListenerService

/**
 * 다른 앱(멜론)의 미디어 세션을 읽으려면 "알림 접근 권한"을 가진
 * NotificationListenerService가 하나 등록되어 있어야 합니다.
 * 이 서비스는 알림 내용을 읽거나 저장하지 않으며, 권한 확보용 껍데기입니다.
 */
class NowPlayingListener : NotificationListenerService()
