# 005. 역할(Role) 분리: shared 엔진 + android-common 어댑터

- 날짜: 2026-09-30
- 상태: 채택

## 배경
CLAUDE.md 가 바뀌어 어느 기기가 아이 옆에 있을지 사용자가 고른다 (시나리오 A: 워치=감지기, B: 폰=감지기).
Phase 0~4 는 워치=감지기로 고정된 코드였고, 감지 로직은 워치 `MonitorService`, 수신 로직은 폰
`ListenerService` / `AudioPlayer` 에 흩어져 있었다. 이대로면 역방향(Phase 3.5)을 위해 같은 코드를 반대쪽 앱에 복사해야 한다.

## 결정

### 1. 로직은 shared 엔진 (순수 Kotlin)
| 엔진 | 맡는 일 |
|---|---|
| `SensorEngine` | 100ms 프레임 → 레벨 → `NoiseDetector` 판정 → 알림 전달, 스트리밍 중이면 같은 프레임 분배, `/control` 처리, 임계값 변경 |
| `ReceiverEngine` | 라이브 듣기 세션 상태, kbps·지연 통계, 무데이터 판단(2초 끊김 / 10초 종료), 끝난 이유 → 사용자 문구, 재동기화 판단, 알림 정책(폰 heads-up / 워치 진동 / 중복 무시) |

- 플랫폼 기능은 포트로 주입: `AlertSink`, `StreamSink` / `StreamSinkFactory`, `Clock`, `EngineLog`.
- 엔진은 포그라운드 서비스를 직접 켜고 끌 수 없으므로 `/control START` / `STOP` 은 `ControlEffect` 로
  "시작/정지/사용자에게 요청" 을 돌려주고 플랫폼이 실행한다.
- 시간 판단을 `Clock` 주입으로 단위 테스트 (Phase 4 에서 기기로만 확인하던 타이밍 로직).

### 2. Android 어댑터는 새 모듈 `:android-common` (Android 라이브러리)
- `AudioCapture`(마이크), `Streamer`(채널 송신, `StreamSink` 구현), `AudioPlayer`(AudioTrack),
  `DataLayerAlertSink`, `ControlClient`(`/control` 요청), `AndroidLog`
- 두 앱이 같은 구현을 쓴다. 권한·서비스 선언은 역할마다 달라 각 앱 매니페스트에 둔다.

### 3. `/control` 규약 (CLAUDE.md §3)
- 라이브 듣기: `STREAM_ON` / `STREAM_OFF` (이전 Phase 4 의 `START` / `STOP` 을 대체)
- 모니터링: `START` / `STOP`. 감지기 앱이 화면에 있으면 바로 시작, 백그라운드면 `ERR:NEEDS_USER` + "탭해서 모니터링 시작" 알림 (마이크 FGS 백그라운드 시작 제한, ADR 004)

### 4. 역할 값
`Role`(SENSOR/RECEIVER) 과 `/status` 모델(`DeviceStatus`)을 shared 에 두고, 선택 화면(Phase 3.5) 전까지
각 앱의 `AppRole.current` 는 시나리오 A 로 고정.

## 대안
- **각 앱에 어댑터 복사** (CLAUDE.md 원래 모듈 구조 유지): 같은 버그를 두 번 고쳐야 함.
  Phase 4 의 AudioTrack 시작 임계값 문제 같은 것이 대표적 → 기각.
- **shared 를 Android 라이브러리로 바꾸기**: 엔진 테스트에 Android 가 끼고, "로직은 순수 Kotlin" 규칙(CLAUDE.md §6)과 충돌 → 기각.

## 진행 방식
`refactor/role-engines` 브랜치에서 단계별 커밋 (0 문서 → 1 targetSdk → 2 모델 → 3 SensorEngine →
4 ReceiverEngine → 5 android-common → 6 규약 전환 → 7 역할 값). 각 단계는 빌드·lint·단위 테스트를 통과.
실기기 회귀는 `docs/test-checklist.md` 시나리오 A.
