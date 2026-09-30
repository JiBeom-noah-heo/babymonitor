# 테스트 체크리스트

실기기 기준 (Galaxy Watch7 SM-L300, Galaxy S26+ SM-S947N). 리팩터링·Phase 완료 때마다 돌린다.
로그 확인: `adb -s <SERIAL> logcat -s WBM/<클래스명>`

## 시나리오 A 회귀 (워치 = 감지기, 폰 = 수신기)

| # | 절차 | 기대 결과 | 확인 로그 |
|---|---|---|---|
| A1 | 폰 앱 "PING 보내기" | 폰 기록에 `PONG (N ms)`, 워치 화면 `PING #N` | `WBM/HomeViewModel: PING ... -> PONG`, `WBM/DataLayerListener: PING from=... role=SENSOR -> PONG` |
| A2 | 워치 "모니터링 시작" | 레벨 바가 소리에 따라 움직임, 조용하면 약 -55 ~ -61 dB | `WBM/MonitorService: monitoring started, threshold=-35.0 dBFS` |
| A3 | 워치 화면 끔 → 워치 옆에서 1~2초 박수 | 폰에 "아기 쪽에서 소리가 나요" heads-up 알림 | 워치 `noise alert level=...`, `alert sent to 1/1 nodes` / 폰 `WBM/DataLayerListener: alert level=...` |
| A4 | A3 후 1분 이상 유지 | 1분마다 heartbeat | `alive: Ns, max=.. dBFS, alerts=N` |
| A5 | 워치 모니터링 **정지** 상태에서 폰 "라이브 듣기" 켜기 | 폰에 "<워치 이름>에서 모니터링을 먼저 시작해 주세요" | 폰 `STREAM_ON -> ERR:NOT_MONITORING`, 워치 `WBM/DataLayerListener: STREAM_ON from=... role=SENSOR -> ERR:NOT_MONITORING` |
| A6 | 워치 모니터링 중 + **워치 화면 켠 채로** 폰 "라이브 듣기" | 2초 안에 워치 주변 소리 들림, 폰 버퍼 약 0.3~0.5초, 약 256 kbps | 폰 `STREAM_ON -> OK`, `channel opened by ...`, 워치 `Streamer: channel opened to ...` |
| A7 | A6 중 폰 스위치 끄기 | 재생 멈춤, 워치 "폰으로 소리 보내는 중" 사라짐 | 워치 `WBM/DataLayerListener: STREAM_OFF ... -> OK`, `stream ended, sent Ns of audio` |
| A8 | A6 중 워치 화면 끔 (알려진 문제, 아래 참고) | 계속 들리면 기록(재동기화·끊김 수). 멈추면 10초 뒤 폰에 "연결 끊김 (10초 동안 소리 없음)" | 폰 `no data for ...ms, closing channel` |

## 역할 전환 · /status
| # | 절차 | 기대 결과 |
|---|---|---|
| R1 | 양쪽 앱 실행 | 각 화면에 상대 역할·모니터링·배터리 표시 (`WBM/StatusHub: peer status ...`) |
| R2 | 한쪽 역할을 상대와 같게 | 양쪽 화면에 "두 기기 모두 ○○예요" 경고 |
| R3 | 감지기 모니터링 on/off | 상대 화면의 "모니터링 중/꺼짐"이 1초 안에 바뀜 |
| R4 | 모니터링 중 역할 변경 | 이전 역할 서비스(모니터링/라이브 듣기)가 멈춤 |

## 시나리오 B (폰 = 감지기, 워치 = 수신기)
| # | 절차 | 기대 결과 | 확인 로그 |
|---|---|---|---|
| B1 | 폰 역할 "감지기", 워치 "수신기로 바꾸기" | 충돌 경고 없음 | `RoleControl: role changed ...` |
| B2 | 폰 "소리 감지" 켜기 (마이크 권한 허용) | 폰에 레벨·기준선, 워치에 "감지기 모니터링 중" | 폰 `WBM/MonitorService: monitoring started, preset=...` |
| B3 | 폰 옆에서 1~2초 박수 | **워치가 길게 3번 진동 + 화면 켜짐 + 레벨 표시** | 폰 `alert sent to 1/1 nodes` / 워치 `WBM/DataLayerListener: alert ...`, `WBM/WatchAlert: vibrated ...` |
| B4 | 워치 "라이브 듣기" | 워치 스피커로 폰 주변 소리 (보조 수단) | 워치 `WBM/ListenerService: STREAM_ON -> OK` |
| B5 | 폰 프리셋 "차 안" | 기준 -25 dB, 말소리 정도로는 알림 없음 | `preset changed to CAR` |
| B6 (완료 조건) | 폰을 차에 두고 워치 차고 10m 밖에서 박수 | 워치 진동 | B3 과 같음 (**미실시**, 차 안 프리셋 값도 이때 조정) |

## 클라우드 경로 (블루투스 끊김, CLAUDE.md §3)
사전: 폰 Galaxy Wearable 앱 클라우드 동기화 켜짐, 폰 핫스팟 설정 (§7 체크리스트)
1. 시나리오 B 로 모니터링 중인지 확인 (B2)
2. 폰 블루투스 끄기 → 워치를 폰 핫스팟 Wi-Fi 에 연결
3. 폰 앱 "연결된 기기"가 `원격(클라우드)` 로 바뀔 때까지 시간 기록 (전환 시간)
4. 폰 옆에서 박수 → 폰 `alert sent` 시각과 워치 `alert` 수신 시각 차이 기록 (두 기기 시계 차이 주의: PING RTT 도 함께 기록)
5. 5회 반복, `docs/devlog/` 에 표로 (전환 시간, 알림 도달 시간, 실패 횟수)
6. 라이브 듣기는 opt-in 으로만 시도, 지연·끊김만 기록

## 원격 START (수신기 → 감지기 모니터링 시작)
폰(수신기) "감지기 모니터링 시작" 버튼:
- 감지기 앱이 화면에 있음 → `START -> OK`, 모니터링 시작
- 감지기 앱이 백그라운드 → `START -> ERR:NEEDS_USER`, 감지기에 "탭해서 모니터링 시작" 알림 → 탭하면 시작
  (2026-09-30: 폰 → 백그라운드 워치 `START -> ERR:NEEDS_USER` 확인)

## 알려진 문제
- 폰이 감지기일 때 통화 중에는 마이크 무음 (2026-09-30 확인)
- A8: 2026-09-29 에는 감지기(워치) 화면이 꺼지고 약 7초 뒤 스트림 정지. 2026-09-30 리팩터링 후 회귀에서는 72초 동안 재현 안 됨
  (워치 Wi-Fi 켜짐 / targetSdk 34, 원인 미확인). 대신 버스트로 재동기화·끊김 증가 → Phase 5 에서 조건별 재측정
