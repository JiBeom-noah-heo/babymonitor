# WatchBabyMonitor — 프로젝트 가이드

갤럭시 워치7과 갤럭시 S26+ 사이에서 한쪽을 아이 옆에 두고, 다른 쪽에서 소리를 듣거나 울음/소음 알림을 받는 **오디오 베이비 모니터** 앱.
워치(Wear OS)와 폰(Android) 두 개의 앱 모듈로 구성되며, **어느 기기가 아이 옆에 있을지는 사용자가 선택(Role)** 한다.

- 시나리오 A (기본): 워치 = 아이 옆 감지기, 폰 = 부모 수신기 (집 안)
- 시나리오 B: 폰 = 아이 옆 감지기 (차 안 등), 워치 = 부모 손목 수신기

> Claude Code 작업 규칙: 이 문서를 먼저 읽고, Phase 순서대로 진행한다. 각 Phase 완료 시 `docs/devlog/` 에 기록을 남긴다.

---

## 1. 목표와 범위

### MVP (반드시)
- 앱 시작 시 역할 선택: **감지기(Sensor)** / **수신기(Receiver)**. 두 기기의 역할은 서로 반대여야 함
- 감지기: 마이크 캡처 → 소음 레벨(dB) 계산 → 임계값 초과 시 수신기에 즉시 알림, 요청 시 실시간 오디오 스트리밍
- 수신기: 알림 수신(폰=heads-up 알림, 워치=진동), 라이브 듣기 토글, 현재 레벨·연결 상태 표시
- 감지·알림·스트리밍 로직은 `shared`에 한 번만 구현하고 양쪽 앱이 역할에 따라 호출
- 양쪽 공통: 시작/정지, 배터리 표시

### 이후 (선택)
- 소음 이벤트 히스토리 (시간 + 짧은 오디오 클립)
- 울음 vs 일반 소음 분류 (간단한 주파수 기반 휴리스틱 → 나중에 on-device ML)
- 폰에서 워치 스피커로 목소리 보내기 (양방향)
- 자장가 재생

### 명시적 제외
- 영상 (워치7에 카메라 없음)
- iOS 지원
- 클라우드/서버 (전부 로컬 통신)

---

## 2. 타겟 기기 & 환경

| 항목 | 값 |
|---|---|
| 워치 | Galaxy Watch7 (Wear OS 5 / One UI Watch 6), minSdk 30 (Wear OS 3+), targetSdk 34 (Wear OS 스토어 최소 요건) |
| 폰 | Galaxy S26+ (One UI), minSdk 29, targetSdk 35 |
| 언어 | Kotlin 2.x |
| UI | Jetpack Compose (폰), Compose for Wear OS (워치) |
| 빌드 | Gradle Kotlin DSL, 버전 카탈로그(`gradle/libs.versions.toml`) |
| 개발 OS | Windows 11, Android Studio 최신 안정 버전 |
| 통신 | Google Play Services Wearable (Data Layer API) |

⚠ Flutter는 사용하지 않는다. Wear OS 지원이 약하고 오디오 스트리밍/포그라운드 서비스 제어에 부적합.

---

## 3. 아키텍처

아래는 시나리오 A(워치=감지기) 기준. 시나리오 B는 좌우가 바뀌며, 코드는 `shared`의 `SensorEngine` / `ReceiverEngine`을 각 앱이 역할에 따라 구동한다.

```
[ Sensor 역할 기기 ]                              [ Receiver 역할 기기 ]
 AudioRecord (16kHz, mono, PCM16)
   └─> SensorEngine (shared)
        ├─ RMS → dB 계산 (100ms 단위)
        ├─ 임계값 판정 (debounce 포함)
        └─ ChannelClient OutputStream ──BT/Cloud──> ChannelClient InputStream
                                                     └─> AudioTrack 재생
 MessageClient ── "/alert" (dB, timestamp) ─────────> 알림 (폰: Notification / 워치: 진동)
 DataClient ── "/status" (연결, 배터리, 역할) ─────> UI 상태 표시
 Foreground Service (MonitorService)                 Foreground Service (ListenerService)
```

### 전송 경로 (Data Layer 동작 방식)
- **블루투스 연결 시**: 기기 간 직접 통신. 실효 거리 ~10m. 지연 낮음
- **블루투스 끊김 시**: Wi-Fi/LTE로 **구글 클라우드 서버를 경유**해 자동 라우팅. 같은 Wi-Fi여도 LAN 직접 통신이 아니라 인터넷을 거침
  - 양쪽 기기 모두 인터넷 필요 (폰: 모바일 데이터, 워치: Wi-Fi·LTE·**폰 핫스팟**)
  - 폰의 Wear OS 앱에서 클라우드 동기화(Cloud sync)가 켜져 있어야 함
  - MessageClient 알림은 충분히 동작. ChannelClient 라이브 오디오는 1~3초 지연·끊김 가능 → UI에 "원격 모드" 표시
  - 블루투스 → 클라우드 전환에 시간이 걸림. 워치를 손목에서 벗어두면 재연결이 더 늦어짐
- 앱은 `NodeClient.isNearby`로 현재 경로(근거리/원격)를 구분해 표시하고, 원격일 때 스트리밍 품질을 낮추거나 알림 전용으로 안내

### 통신 규약 (Wearable Data Layer)
| 채널/경로 | API | 방향 | 내용 |
|---|---|---|---|
| `/audio` | ChannelClient | 감지기 → 수신기 | 원시 PCM16 LE 스트림 (헤더 없음, 고정 포맷). 감지기가 채널을 연다 |
| `/alert` | MessageClient | 감지기 → 수신기 | `{level: Float, ts: Long, kind: "NOISE"}` JSON |
| `/status` | DataClient | 양방향 | 역할(SENSOR/RECEIVER), 모니터링 on/off, 스트리밍, 배터리 %, 마이크 막힘, 감지 설정(감지기), 오류 |
| `/control` | MessageClient (`sendRequest`, ADR 002) | 수신기 → 감지기 | `START`, `STOP`, `STREAM_ON`, `STREAM_OFF`, `SET_THRESHOLD:<dB>`, `SET_COOLDOWN:<ms>`, `SET_PRESET:<HOME\|CAR>`(ADR 007), `PING`(진단). 응답 `OK` / `PONG` / `ERR:<사유>` |

- 경로 이름은 방향과 무관하게 동일. 어느 기기가 보내는지는 `/status`의 역할 값으로 판단한다.
- 양쪽 역할이 같으면(둘 다 SENSOR 등) 수신기 UI에 경고를 띄운다.
- `STREAM_ON`은 감지기가 모니터링 중일 때만 `OK`, 아니면 `ERR:NOT_MONITORING` (ADR 004).
- `START`(원격 모니터링 시작)는 감지기 앱이 화면에 떠 있거나 이미 모니터링 중일 때만 가능. 백그라운드에서는 마이크 포그라운드 서비스를 새로 시작할 수 없으므로 `ERR:NEEDS_USER`로 응답하고, 감지기 기기에 "탭해서 모니터링 시작" 알림을 띄운다.

- 직렬화는 kotlinx.serialization JSON. 오디오 스트림만 바이너리.
- 두 모듈이 공유하는 상수/모델은 `shared` 모듈(Kotlin 라이브러리)에 둔다.

### 모듈 구조
```
WatchBabyMonitor/
├── mobile/          # 폰 앱: 화면(Compose), Application
│   └── src/main/java/.../mobile/ui/
├── wear/            # 워치 앱: 화면(Compose for Wear OS), Application
│   └── src/main/java/.../wear/ui/
├── android-common/  # 두 앱이 함께 쓰는 Android 코드 (Android 라이브러리, ADR 005·006)
│   └── src/main/java/.../common/
│       ├── audio/         # AudioCapture(마이크), Streamer(채널 송신), AudioPlayer(AudioTrack)
│       ├── datalayer/     # ControlClient, DataLayerAlertSink, StatusSync(/status)
│       ├── service/       # MonitorService(감지기 FGS), ListenerService(수신기 FGS), DataLayerListenerService, StartPrompt
│       ├── notification/  # NoiseNotifications(폰 heads-up), WatchAlert(워치 진동+화면 켜기), PeerNotifications
│       ├── history/       # Room 소음 이벤트 기록 (ADR 007)
│       └── Engines, RoleStore, RoleControl, StatusHub   # 엔진 보관, 역할·프리셋 저장, /status 동기화
├── shared/          # 순수 Kotlin: 상수, 데이터 모델, 경로, 엔진 (Android 의존성 없음)
│   ├── Role.kt            # SENSOR / RECEIVER
│   ├── SensorEngine.kt    # dB 계산, 임계값 판정, debounce, 스트림 분배
│   └── ReceiverEngine.kt  # 알림 정책, 스트림 수신 상태 관리
├── docs/
│   ├── devlog/      # 날짜별 작업 기록 (콘텐츠 재활용용)
│   └── decisions/   # 기술 결정 기록 (ADR)
└── CLAUDE.md
```

---

## 4. 핵심 기술 결정 (변경 시 docs/decisions/ 에 ADR 작성)

1. **오디오 포맷**: 16kHz / mono / PCM 16bit. 음성 감지에 충분하고 BT 대역폭 안에 들어옴 (256kbps). 압축(Opus)은 Phase 5 이후 검토.
2. **dB 계산**: 100ms 버퍼의 RMS → `20 * log10(rms / 32768)` 로 dBFS. 절대 dB SPL은 워치마다 다르므로 상대값 + 사용자 캘리브레이션.
3. **알림 debounce**: 임계값 초과가 1초 이상 지속 시 1회 알림, 이후 30초 쿨다운. 짧은 소음으로 폭주 방지.
4. **스트리밍 vs 알림만 모드**: 기본은 "알림만 + 필요 시 라이브 듣기". 상시 스트리밍은 배터리 때문에 사용자가 켤 때만.
5. **포그라운드 서비스 타입**: 감지기 `microphone`, 수신기 `mediaPlayback`. Android 14 이후 타입 명시 필수. `connectedDevice`는 추가 런타임 권한이 필요하고 재생에 불필요해 제외 (ADR 004).
6. **화면 꺼짐 대응**: 워치 앱은 `WakeLock`(PARTIAL) + 포그라운드 서비스로 유지. Ambient 모드 진입해도 서비스는 살아있어야 함. WakeLock 은 1시간 상한을 30분마다 갱신, 녹음 중 `AudioIn` 과 겹쳐 추가 소모는 없음 (ADR 008). 화면은 보일 때만 상태를 수집하고 레벨 표시는 250ms (ADR 008).
7. **역할(Role) 분리**: 감지·수신 로직은 `shared`에 플랫폼 독립적으로 두고, 마이크/스피커/Data Layer 같은 플랫폼 어댑터는 `android-common`에 한 번만 구현해 두 앱이 함께 쓴다 (ADR 005). 워치가 수신기일 때 알림은 **진동 우선**, 스피커는 보조. 워치가 감지기일 때 폰이 수신기면 heads-up 알림.
8. **원격(클라우드) 모드**: 블루투스가 끊겨 클라우드 경유로 전환되면 자동으로 알림 전용 모드를 권장하고, 라이브 듣기는 사용자가 명시적으로 켤 때만. 차 안 시나리오에서는 감지기(폰)의 소음 임계값을 별도 프리셋으로 둔다 (에어컨·주행 소음 오탐 대응).

---

## 5. 개발 Phase

각 Phase 는 독립적으로 빌드·실행 가능해야 한다. 완료 조건을 만족하면 다음으로 넘어간다.

### Phase 0 — 프로젝트 골격
> 최신 Android Studio에는 "Wear OS + Phone" 통합 템플릿이 없다. 폰 프로젝트를 먼저 만들고 워치 모듈을 추가하는 방식으로 진행한다.
- [x] `File > New > New Project` → **Phone and Tablet > Empty Activity** (Compose) 로 프로젝트 생성. 모듈명은 기본 `app` 대신 `mobile` 로 리팩터 (또는 생성 후 rename)
- [x] `File > New > New Module` → **Wear OS > Empty Wear App** 으로 `wear` 모듈 추가
- [x] `File > New > New Module` → **Java or Kotlin Library** 로 `shared` 모듈 추가, `mobile`/`wear` 양쪽에서 `implementation(project(":shared"))`
- [x] `wear` 매니페스트에서 `com.google.android.wearable.standalone` 메타데이터를 `false` 로 (폰 앱 의존)
- [x] 버전 카탈로그 설정, 공통 의존성 (play-services-wearable, compose, kotlinx-serialization)
- [x] 두 앱 `applicationId` 동일하게 (Data Layer 연결 조건)
- [x] 워치·폰 각각 빈 화면 실행 확인
- **완료 조건**: 양쪽 앱이 실제 기기에서 설치·실행됨

### Phase 1 — 연결 확인
- [x] `shared`에 경로 상수 정의
- [x] 폰 → 워치 `/control` 메시지 "PING", 워치가 "PONG" 회신
- [x] `NodeClient`로 연결된 노드 목록 표시
- **완료 조건**: 버튼 누르면 왕복 메시지가 양쪽 화면에 뜸

### Phase 2 — 워치 마이크 & 레벨 미터
- [x] `RECORD_AUDIO` 권한 요청 (워치 UI)
- [x] `AudioCapture`: AudioRecord 16kHz mono PCM16, 코루틴 Flow로 버퍼 방출
- [x] `LevelMeter`: RMS → dBFS, 100ms 단위 StateFlow
- [x] 워치 화면에 실시간 레벨 바 표시
- **완료 조건**: 워치 화면에서 소리 크기에 따라 바가 움직임

### Phase 3 — 소음 알림
- [x] 워치 `MonitorService` (foreground, type=microphone) 생성, 시작/정지
- [x] 임계값 판정 + debounce/cooldown 로직 (`shared`에 순수 Kotlin으로, 단위 테스트 필수)
- [x] `/alert` MessageClient 전송
- [x] 폰 `WearableListenerService`로 수신 → 알림 채널 "소음 감지" 로 heads-up 알림
- [x] 워치 화면 끈 채로 10분 동작 확인
- **완료 조건**: 워치 옆에서 박수 치면 다른 방의 폰이 울림

### Phase 3.5 — 역방향(폰=감지기, 워치=수신기) 검증
- [x] 역할 선택 화면 양쪽 앱에 추가, `/status`로 역할 동기화, 역할 충돌 경고
- [x] 폰 `MonitorService`: 워치와 동일한 `SensorEngine` 사용, 마이크 캡처 + 알림 전송
- [x] 워치 수신기: `/alert` 수신 → 진동 패턴 + 화면 켜기 + 레벨 표시
- [x] 차 안 프리셋(임계값 높게, 쿨다운 길게) 설정에 추가 (값은 실차 측정 전 초기값)
- [ ] 클라우드 경로 테스트: 폰 블루투스 끄고 워치를 폰 핫스팟에 연결 → 알림 도달 시간 측정, `docs/devlog/`에 기록
- **완료 조건**: 폰을 차에 두고 워치를 차고 10m 밖에서 박수 소리에 워치가 진동함 — _통과 (2026-09-30, 사용자 확인)_

### Phase 4 — 라이브 오디오 스트리밍
> Phase 0~4 는 역할 분리 이전(워치=감지기 고정)에 완료. 이후 `refactor/role-engines` 에서 Role / SensorEngine / ReceiverEngine 구조로 이전.
- [x] 워치 `Streamer`: ChannelClient.openChannel → OutputStream에 PCM 쓰기
- [x] 폰 `ListenerService`: onChannelOpened → InputStream 읽어 AudioTrack 재생
- [x] 폰 UI: "라이브 듣기" 토글, 지연/끊김 표시
- [x] 폰 → 워치 `/control` START/STOP으로 스트리밍 제어
- **완료 조건**: 폰에서 워치 주변 소리가 2초 이내 지연으로 들림

### Phase 5 — 안정성 & 배터리
- [x] **감지기 화면 꺼진 상태에서 라이브 스트림 유지** (09-29 약 7초 뒤 정지 → 09-30 재현 안 됨, 워치 감지기 A8 사용자 확인 통과. ADR 004)
- [x] 연결 끊김 감지 (CapabilityClient) → 양쪽 UI에 표시, 자동 재연결 (원격은 PING 으로 재확인)
- [x] 워치 배터리 %를 `/status`로 전송, 20% 이하 시 폰 알림 (감지기 배터리 → 수신기 알림. 20% 알림은 단위 테스트만)
- [x] 1시간 연속 동작 배터리 소모 측정 → `docs/devlog/` 기록 (2026-09-30: 워치 감지기 알림 모드 **18%/시간**, 무선 디버깅 켜진 채)
- [x] Doze / 앱 대기 버킷 영향 테스트 (폰 감지기 force-idle 3분, restricted 버킷 2분: 영향 없음)
- **완료 조건**: 알림 모드 1시간 동작 시 워치 배터리 소모 15% 이하 (목표치, 측정 후 조정) — _미달: 18%/시간. 무선 디버깅 끈 재측정 또는 소모 줄이기 필요_

### Phase 6 — 마무리
- [x] 설정 화면 (임계값 슬라이더, 쿨다운, 자동 스트리밍 여부) — 수신기에서 감지기 설정 원격 변경, 원격 듣기 opt-in (ADR 007)
- [x] 소음 이벤트 히스토리 (Room) — 오디오 클립 없이, 30일 보관
- [x] 앱 아이콘 (적응형 + 테마 아이콘) — 워치 타일(선택)은 하지 않음

---

## 6. 코딩 규칙

- 코루틴 + Flow 기반. 콜백 API(Data Layer)는 `suspendCancellableCoroutine` 또는 `callbackFlow`로 감싼다.
- 오디오 처리 로직(RMS, 임계값, debounce)은 **Android 의존성 없는 순수 Kotlin**으로 `shared`에 두고 JUnit 테스트 작성.
- 하드코딩 금지: 경로, 샘플레이트, 임계값 기본값은 전부 `shared/Constants.kt`.
- 서비스에서 예외 삼키지 않기. 실패는 로그 + `/status`로 폰에 전파.
- 로그 태그: `WBM/<클래스명>`. 오디오 버퍼 내용은 절대 로그에 찍지 않음.
- 각 Phase 시작 전 브랜치 `phase/N-이름` 생성, 완료 시 main 머지.

---

## 7. 테스트 & 실행

### 기기 준비 체크리스트 (최초 1회, 사람이 직접 수행)
Claude Code는 이 항목들이 끝났다고 가정한다. adb에 기기가 안 잡히면 여기부터 다시 확인.

**갤럭시 S26+ (폰)**
- [ ] 설정 > 휴대전화 정보 > 소프트웨어 정보 > 빌드 번호 7번 탭 → 개발자 옵션 활성화
- [ ] 설정 > 보안 및 개인정보 보호 > **보안 위험 자동 차단(Auto Blocker) 끄기** — 켜져 있으면 USB 디버깅이 "차단함"으로 비활성화됨. 프로젝트 끝나면 다시 켜기
- [ ] 개발자 옵션 > USB 디버깅 켜기 → USB 연결 시 "이 컴퓨터에서 항상 허용" 체크
- [ ] adb에 안 잡히면 Windows에 삼성 USB 드라이버 설치

**갤럭시 워치7 (워치)**
- [ ] 설정 > 워치 정보 > 소프트웨어 정보 > 소프트웨어 버전 7번 탭 → 개발자 옵션 활성화
- [ ] 설정 > 연결 > Wi-Fi → Wi-Fi 글자 부분 탭 → **항상 켜기** (기본 "자동"은 블루투스 연결 중엔 Wi-Fi를 끔). 배터리 아끼려면 "자동" 유지 + 개발 중엔 충전기에 올려두기(충전 중엔 자동 모드에서도 Wi-Fi 켜짐)
- [ ] 워치와 PC를 같은 Wi-Fi에 연결
- [ ] 개발자 옵션 > ADB 디버깅 켜기 → Wi-Fi를 통한 디버깅(무선 디버깅) 켜기
- [ ] 무선 디버깅 > 새 기기 페어링 → PC에서 `adb pair <IP>:<페어링포트>` + 6자리 코드 (최초 1회)
- [ ] `adb connect <IP>:<연결포트>` — **페어링 포트와 연결 포트는 다름**. 재부팅·Wi-Fi 재연결 시 포트가 바뀌므로 connect만 다시 실행
- [ ] 페어링 화면은 워치 화면이 꺼지면 사라짐 → 화면 켜짐 시간 늘려두기

**Phase 3.5 / 5 테스트용**
- [ ] 폰 Wear OS(Galaxy Wearable) 앱에서 클라우드 동기화 켜져 있는지 확인
- [ ] 폰 핫스팟 설정해두고 워치가 붙는지 확인
- [ ] 배터리 측정 시에는 워치를 충전기에서 내리고 Wi-Fi "항상 켜기"로 전환

```bash
# 빌드
./gradlew :mobile:assembleDebug :wear:assembleDebug

# 단위 테스트 (shared 로직)
./gradlew :shared:test

# 설치 (기기 2대 연결 시 -s 로 시리얼 지정)
adb devices
adb -s <PHONE_SERIAL> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <WATCH_SERIAL> install -r wear/build/outputs/apk/debug/wear-debug.apk

# 워치 무선 디버깅 연결 (워치 개발자 옵션 > 무선 디버깅 > 페어링)
adb pair <IP>:<PORT>
adb connect <IP>:<PORT>

# 로그
adb -s <SERIAL> logcat -s WBM
```

- 실기기 테스트 필수. 에뮬레이터는 Data Layer 연결이 안정적이지 않음.
- Phase 3 이후 테스트 체크리스트: `docs/test-checklist.md` 에 유지.

---

## 8. 알려진 함정

- 워치와 폰 앱의 `applicationId`와 서명 키가 다르면 Data Layer 통신이 안 됨.
- `ChannelClient` 스트림은 연결 끊기면 예외 없이 멈출 수 있음 → 타임아웃으로 감지.
- Wear OS는 백그라운드 마이크 접근을 포그라운드 서비스 없이는 차단함.
- 삼성 배터리 최적화가 서비스를 죽일 수 있음 → 설정에서 앱 "제한 없음" 안내 필요.
- AudioRecord 버퍼 크기는 `getMinBufferSize()` 의 2배 이상으로.
- 블루투스 실효 거리 ~10m, 벽 통과 시 급감. 블루투스가 끊기면 Data Layer가 Wi-Fi/LTE로 **구글 클라우드를 경유**해 자동 전환 — 같은 Wi-Fi라도 LAN 직접 연결이 아님. 양쪽 기기 모두 인터넷 필요, 지연 증가.
- 클라우드 경유 시 아이 소리가 (암호화되어) 구글 서버를 거침. 설정 화면에 이 사실을 명시하고 원격 스트리밍은 opt-in.
- 워치 Wi-Fi 기본 모드 "자동"은 블루투스 연결 중 Wi-Fi를 끔 → 무선 디버깅이 갑자기 끊기면 이것부터 확인.
- 삼성 Auto Blocker가 켜져 있으면 폰 USB 디버깅 자체가 잠김.
- 워치 스피커는 작음 → 워치가 수신기일 때 라이브 오디오는 보조 수단, 진동이 주 알림.
- 차 안은 배경 소음(에어컨·주행)이 커서 집 안 임계값을 그대로 쓰면 오탐 폭주. 프리셋 분리 필수.
- CapabilityClient 가 "원격(클라우드)으로 닿음"이라고 해도 실제로는 안 닿을 수 있음 (폰 완전 오프라인에서 확인) → 원격일 땐 PING 으로 재확인.
- 잠든 기기에서는 코루틴 `delay()` 타이머도 멈춤 → 제때 필요한 판단(끊김 유예 등)은 짧게 WakeLock.
- 클라우드 경유 오디오는 1~2초씩 몰려 옴 → 근거리용 작은 재생 버퍼면 대부분 버려짐.
- 폰이 감지기일 때 **통화 중에는 마이크가 무음**(Android 가 통화에 우선 배정). 통화가 끝나면 자동 복구되지만 그동안 아이 소리를 놓침.

---

## 9. 기록 (콘텐츠 재활용)

이 프로젝트는 나중에 블로그/영상 콘텐츠로 정리할 예정이므로 다음을 꾸준히 남긴다.

- `docs/devlog/YYYY-MM-DD.md`: 그날 한 일, 막힌 점, 해결 방법, 스크린샷 경로
- `docs/decisions/NNN-제목.md`: 왜 이 방식을 택했는지 (대안 포함)
- 측정 데이터(배터리, 지연 시간)는 표로 기록
- Phase 완료 시 짧은 데모 영상 촬영 (`docs/media/`)

Claude Code는 Phase 완료 시 devlog 초안을 자동으로 작성한다.

---

## 10. Claude Code 명령 예시

프로젝트 루트에서 `claude` 실행 후 그대로 붙여 넣어 쓴다.

```
Phase 0 진행해줘. 폰 프로젝트에 wear, shared 모듈을 추가하고 빌드가 되는지 확인해.
```
```
Phase 1 진행해줘. 완료되면 adb로 양쪽 기기에 설치하고 PING/PONG 로그를 보여줘.
```
```
shared 모듈에 Role(SENSOR/RECEIVER), SensorEngine, ReceiverEngine 골격을 만들어줘.
SensorEngine의 dB 계산과 debounce 로직은 단위 테스트부터 작성해.
```
```
Phase 3 진행해줘. 워치가 감지기 역할일 때 폰에 heads-up 알림이 오는지까지.
```
```
Phase 3.5 진행해줘. 폰을 감지기, 워치를 수신기로 바꾸고 워치 진동 알림을 구현해.
차 안 프리셋도 설정에 추가해.
```
```
클라우드 경유 모드 테스트 시나리오를 docs/test-checklist.md에 정리해줘.
블루투스 끊김 → 핫스팟 연결 → 알림 도달 시간 측정 순서로.
```
```
Phase 5 배터리 측정 결과를 docs/devlog/오늘날짜.md에 표로 정리해줘.
```
```
오늘 작업 내용으로 devlog 초안 작성하고, 블로그 글로 쓸 만한 포인트 3개 뽑아줘.
```

Claude Code에게 기기 설정(개발자 옵션, Auto Blocker, Wi-Fi 모드)을 시키지 않는다 — 그건 사람이 직접 한다 (7번 체크리스트).