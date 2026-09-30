# 테스트 체크리스트

실기기 기준 (Galaxy Watch7 SM-L300, Galaxy S26+ SM-S947N). 리팩터링·Phase 완료 때마다 돌린다.
로그 확인: `adb -s <SERIAL> logcat -s WBM/<클래스명>`

## 시나리오 A 회귀 (워치 = 감지기, 폰 = 수신기)

| # | 절차 | 기대 결과 | 확인 로그 |
|---|---|---|---|
| A1 | 폰 앱 "PING 보내기" | 폰 기록에 `PONG (N ms)`, 워치 화면 `PING #N` | `WBM/HomeViewModel: PING ... -> PONG`, `WBM/ControlReceiver: PING from=...` |
| A2 | 워치 "모니터링 시작" | 레벨 바가 소리에 따라 움직임, 조용하면 약 -55 ~ -61 dB | `WBM/MonitorService: monitoring started, threshold=-35.0 dBFS` |
| A3 | 워치 화면 끔 → 워치 옆에서 1~2초 박수 | 폰에 "아기 쪽에서 소리가 나요" heads-up 알림 | 워치 `noise alert level=...`, `alert sent to 1/1 nodes` / 폰 `WBM/WearMessageReceiver: alert level=...` |
| A4 | A3 후 1분 이상 유지 | 1분마다 heartbeat | `alive: Ns, max=.. dBFS, alerts=N` |
| A5 | 워치 모니터링 **정지** 상태에서 폰 "라이브 듣기" 켜기 | 폰에 "<워치 이름>에서 모니터링을 먼저 시작해 주세요" | 폰 `STREAM_ON -> ERR:NOT_MONITORING`, 워치 `STREAM_ON from=... -> ERR:NOT_MONITORING` |
| A6 | 워치 모니터링 중 + **워치 화면 켠 채로** 폰 "라이브 듣기" | 2초 안에 워치 주변 소리 들림, 폰 버퍼 약 0.3~0.5초, 약 256 kbps | 폰 `STREAM_ON -> OK`, `channel opened by ...`, 워치 `Streamer: channel opened to ...` |
| A7 | A6 중 폰 스위치 끄기 | 재생 멈춤, 워치 "폰으로 소리 보내는 중" 사라짐 | 워치 `STREAM_OFF from=... -> OK`, `stream ended, sent Ns of audio` |
| A8 | A6 중 워치 화면 끔 (알려진 문제, 아래 참고) | 계속 들리면 기록(재동기화·끊김 수). 멈추면 10초 뒤 폰에 "연결 끊김 (10초 동안 소리 없음)" | 폰 `no data for ...ms, closing channel` |

## 원격 START (수신기 → 감지기 모니터링 시작)
엔진 단위 테스트로 검증 (`SensorEngineTest`). 폰 UI 에 원격 시작 버튼이 생기면(Phase 3.5) 실기기 항목 추가:
- 워치 앱이 화면에 있음 → `START -> OK`, 모니터링 시작
- 워치 앱이 백그라운드 → `START -> ERR:NEEDS_USER`, 워치에 "탭해서 모니터링 시작" 알림 → 탭하면 시작

## 알려진 문제
- A8: 2026-09-29 에는 감지기(워치) 화면이 꺼지고 약 7초 뒤 스트림 정지. 2026-09-30 리팩터링 후 회귀에서는 72초 동안 재현 안 됨
  (워치 Wi-Fi 켜짐 / targetSdk 34, 원인 미확인). 대신 버스트로 재동기화·끊김 증가 → Phase 5 에서 조건별 재측정
