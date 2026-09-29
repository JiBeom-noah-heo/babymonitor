# 002. `/control` 은 MessageClient 요청/응답(RPC)으로 보낸다

- 날짜: 2026-09-29
- 상태: 채택

## 배경
Phase 1 에서 폰이 `/control` 로 PING 을 보내면 워치가 PONG 을 돌려줘야 한다.
그런데 CLAUDE.md §3 통신 규약에서 `/control` 은 폰 → 워치 한 방향으로만 정의돼 있다.

## 결정
`MessageClient.sendRequest(nodeId, "/control", bytes)` 로 보내고, 워치는
`WearableListenerService.onRequest()` 에서 응답 바이트를 `Task<ByteArray>` 로 돌려준다.

- 워치 매니페스트: `ControlReceiver` 서비스에 `com.google.android.gms.wearable.REQUEST_RECEIVED`,
  `wear://*/control` 필터.
- 명령 와이어 포맷은 `shared/ControlCommand` 가 담당 (`PING`, `START`, `STOP`, `SET_THRESHOLD:<dB>`, UTF-8).
- 응답: `PONG`, 또는 `ERR:UNKNOWN` / `ERR:UNSUPPORTED`.
- 폰은 `CONTROL_REQUEST_TIMEOUT_MS`(5초) 안에 응답이 없으면 실패로 처리.

## 이유
- 응답 전용 경로(예: `/control-reply`)를 새로 만들 필요가 없다. 통신 규약 표가 그대로 유지된다.
- 요청과 응답이 Task 하나로 묶이므로 왕복 시간(RTT)을 바로 잴 수 있고, 여러 요청이 동시에 나가도 짝이 섞이지 않는다.
- 이후 START/STOP 도 같은 경로로 보내면 워치가 "명령을 받아 실행했는지"를 응답으로 확인해 줄 수 있다.
- `WearableListenerService` 라서 워치 앱이 꺼져 있어도 Play Services 가 서비스를 깨운다.

## 대안
- `sendMessage` + 워치 → 폰 응답 경로 추가: 단방향 메시지 두 개를 직접 짝지어야 하고, 규약 표에 경로가 늘어남 → 기각.
- 액티비티에서 `addRpcService` 로 리스너 등록: 앱이 떠 있을 때만 동작 → 기각.

## 측정 (Galaxy Watch7 SM-L300 ↔ SM-S947N, 같은 Wi-Fi + BT)
| 회차 | RTT |
|---|---|
| 1 | 461 ms |
| 2 | 571 ms |
| 3 | 238 ms |
| 4 | 250 ms |
