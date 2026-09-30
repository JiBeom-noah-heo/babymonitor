package com.watchbabymonitor.common

import com.watchbabymonitor.shared.Role

/** 화면에 보이는 역할 이름. */
val Role.label: String
    get() = when (this) {
        Role.SENSOR -> "감지기"
        Role.RECEIVER -> "수신기"
    }
