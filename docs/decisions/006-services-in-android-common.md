# 006. 서비스·리스너도 android-common 으로 (두 앱이 모든 역할 수행)

- 날짜: 2026-09-30
- 상태: 채택 (ADR 005 확장)

## 배경
Phase 3.5 부터 폰도 감지기, 워치도 수신기가 될 수 있다 (CLAUDE.md §1 시나리오 B).
ADR 005 까지는 감지기 서비스(`MonitorService`, `ControlReceiver`)가 wear 에, 수신기 서비스
(`ListenerService`, `WearMessageReceiver`)가 mobile 에만 있었다.

## 결정
- `MonitorService`(마이크 FGS), `ListenerService`(재생 FGS), 알림(`NoiseNotifications`, `WatchAlert`, `StartPrompt`)을
  `android-common` 으로 옮기고, **서비스 선언과 역할별 권한도 라이브러리 매니페스트**에 둔다 → 두 앱에 병합.
- `ControlReceiver` + `WearMessageReceiver` → `DataLayerListenerService` 하나. 역할을 보고 처리:
  - `/control`: 감지기면 `SensorEngine`, `PING` 은 역할과 무관하게 `PONG`, 그 외엔 `ERR:WRONG_ROLE`
  - `/alert`: 수신기면 `ReceiverEngine` 이 정한 방식(폰 heads-up / 워치 진동+화면 켜기)으로, 감지기면 무시(역할 충돌)
- 역할·프리셋은 `RoleStore`(SharedPreferences) — 처음엔 시나리오 A 기본값. 역할을 바꾸면 이전 역할 서비스를 멈춘다(`RoleControl`).
- `/status`(DataClient, `StatusHub`/`StatusSync`)로 서로의 역할·모니터링·배터리를 공유하고 역할 충돌을 경고.
- 라이브러리는 앱의 `MainActivity` 를 모르므로 알림 탭은 `getLaunchIntentForPackage` + 액션(`ACTION_START_MONITORING`)으로 연다.
- 앱 모듈에는 화면(Activity, Compose)과 `Application`(StatusHub 시작)만 남는다.

## 결과 / 영향
- 두 앱 모두 `RECORD_AUDIO`, 마이크·재생 FGS, `VIBRATE` 권한을 선언한다 (역할에 따라 실제로 요청하는 것만 다름).
- 폰 minSdk 29 에서 감지기가 될 수 있으므로 마이크 FGS 타입은 API 30+ 에서만 지정.
- 로그 태그: `WBM/DataLayerListener` (이전 `ControlReceiver` / `WearMessageReceiver`).

## 대안
- 역할별 서비스를 각 앱에 복사: 매니페스트·서비스가 두 벌이 되어 ADR 005 의 이유(한 곳에서 고치기)와 충돌 → 기각.
