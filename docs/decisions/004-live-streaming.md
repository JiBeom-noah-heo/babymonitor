# 004. 라이브 스트리밍 구조

- 날짜: 2026-09-29
- 상태: 채택 (워치 화면 꺼짐 시 스트림 정지 문제는 Phase 5 에서 처리)

## 결정

### 1. 스트리밍은 워치 모니터링이 켜져 있을 때만
`/control START` 를 받으면 워치는 **이미 돌고 있는** `MonitorService` 의 녹음 프레임을
`Streamer` 로 함께 보낸다. 모니터링이 꺼져 있으면 `ERR:NOT_MONITORING` 으로 거절한다.

- 이유: Android 11+ 는 앱이 백그라운드일 때 마이크 포그라운드 서비스 시작을 막는다.
  `/control` 은 `WearableListenerService`(백그라운드)로 들어오므로 여기서 녹음을 새로 시작할 수 없다.
- CLAUDE.md §4-4 기본값("알림만 + 필요 시 라이브 듣기")과도 맞는다: 모니터링은 항상 켜 두고, 듣고 싶을 때만 스트림을 붙인다.
- 녹음기는 하나(AudioRecord 1개). 레벨 판정과 스트리밍이 같은 100ms 프레임을 공유한다.

### 2. 채널 방향: 워치가 연다
폰 `START` 요청 → 워치가 `ChannelClient.openChannel(폰, "/audio")` → 폰은 `ListenerService` 에서
등록한 `ChannelCallback.onChannelOpened` 로 받는다. (CLAUDE.md §3 그대로)

### 3. 폰 포그라운드 서비스 타입: `mediaPlayback` 만
CLAUDE.md §4-5 는 `mediaPlayback + connectedDevice` 였으나, `connectedDevice` 는 Android 14 에서
블루투스/UWB 등 추가 런타임 권한이 필요하고 재생에는 필요 없어 제외.

### 4. 지연 관리
| 위치 | 방법 | 값 |
|---|---|---|
| 워치 송신 대기열 | 넘치면 오래된 프레임부터 버림 | 5프레임(0.5초) |
| 폰 재생 시작 | 이만큼 모이면 재생 (`setStartThresholdInFrames`) | 0.3초 |
| 폰 재생 대기량 상한 | 넘으면 AudioTrack 재생성 → 오래된 소리 버리고 최신부터 | 0.6초 |
| 끊김 표시 | 데이터 없음 | 2초 |
| 연결 끊김 판정 | 데이터 없음 → 채널 닫고 종료 (CLAUDE.md §8) | 10초 |

- 오디오 포맷은 그대로 16kHz / mono / PCM16 LE, 헤더 없음 (약 256kbps).

## 대안
- **폰에서 START 시 워치 모니터링까지 자동 시작**: 백그라운드 마이크 FGS 제한으로 불가.
- **재생 대기량 초과 시 새 데이터를 버림** (첫 구현): 트랙이 underrun 으로 비활성화된 동안 write 를 멈춰서 영영 재시작 안 됨.
- **pause + flush 로 재동기화**: flush 후 재생 위치가 `play()` 이후에야 0 으로 바뀌어 대기량 계산이 어긋남 → 재생성으로 변경.

## 알려진 문제 (Phase 5)
- **워치 화면이 꺼지고 약 7초 뒤 워치 → 폰 채널 전송이 완전히 멈춘다.** MonitorService·WakeLock 은 살아 있고,
  짧은 `/alert` 메시지는 전달된다(Phase 3). 폰은 10초 뒤 "연결 끊김"으로 정리한다.
- 워치가 절전 중일 때 `/control` 요청 응답이 5초를 넘기기도 함.
- 후보: 스트리밍 중 워치 화면 어둡게 유지, 비트레이트 절반(8kHz), Opus 압축, Data Layer 대신 Wi-Fi 직접 소켓.
