# WatchBabyMonitor — 프로젝트 가이드

갤럭시 워치7을 아이 옆에 두고, 폰에서 소리를 듣거나 울음/소음 알림을 받는 **오디오 베이비 모니터** 앱.
워치(Wear OS)와 폰(Android) 두 개의 앱 모듈로 구성된다.

> Claude Code 작업 규칙: 이 문서를 먼저 읽고, Phase 순서대로 진행한다. 각 Phase 완료 시 `docs/devlog/` 에 기록을 남긴다.

---

## 1. 목표와 범위

### MVP (반드시)
- 워치 마이크로 소리를 캡처해 폰으로 실시간 스트리밍 (라이브 리스닝)
- 워치에서 소음 레벨(dB) 계산 → 임계값 초과 시 폰에 즉시 알림
- 폰 앱: 라이브 오디오 재생, 현재 소음 레벨 표시, 알림 수신, 연결 상태 표시
- 워치 앱: 시작/정지 버튼, 현재 레벨 표시, 배터리 표시

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
| 워치 | Galaxy Watch7 (SM-L300, Wear OS 5 / One UI Watch 6), minSdk 30 (Wear OS 3+), targetSdk 33 |
| 폰 | Android 10+ (minSdk 29), targetSdk 34 |
| 언어 | Kotlin 2.x |
| UI | Jetpack Compose (폰), Compose for Wear OS (워치) |
| 빌드 | Gradle Kotlin DSL, 버전 카탈로그(`gradle/libs.versions.toml`) |
| 개발 OS | Windows 11, Android Studio 최신 안정 버전 |
| 통신 | Google Play Services Wearable (Data Layer API) |

⚠ Flutter는 사용하지 않는다. Wear OS 지원이 약하고 오디오 스트리밍/포그라운드 서비스 제어에 부적합.

---

## 3. 아키텍처

```
[ Watch (wear 모듈) ]                          [ Phone (mobile 모듈) ]
 AudioRecord (16kHz, mono, PCM16)
   └─> AudioProcessor
        ├─ RMS → dB 계산 (100ms 단위)
        ├─ 임계값 판정 (debounce 포함)
        └─ ChannelClient OutputStream ──BT/WiFi──> ChannelClient InputStream
                                                    └─> AudioTrack 재생
 MessageClient ── "/alert" (dB, timestamp) ────────> 알림 Notification
 DataClient ── "/status" (연결, 배터리, 상태) ────> UI 상태 표시
 Foreground Service (MonitorService)                Foreground Service (ListenerService)
```

### 통신 규약 (Wearable Data Layer)
| 채널/경로 | API | 방향 | 내용 |
|---|---|---|---|
| `/audio` | ChannelClient | 워치 → 폰 | 원시 PCM16 스트림 (헤더 없음, 고정 포맷) |
| `/alert` | MessageClient | 워치 → 폰 | `{level: Float, ts: Long, kind: "NOISE"}` JSON |
| `/status` | DataClient | 양방향 | 모니터링 on/off, 워치 배터리 %, 마지막 dB |
| `/control` | MessageClient | 폰 → 워치 | `START`, `STOP`, `SET_THRESHOLD:<dB>` |

- 직렬화는 kotlinx.serialization JSON. 오디오 스트림만 바이너리.
- 두 모듈이 공유하는 상수/모델은 `shared` 모듈(Kotlin 라이브러리)에 둔다.

### 모듈 구조
```
WatchBabyMonitor/
├── mobile/          # 폰 앱
│   └── src/main/java/.../mobile/
│       ├── ui/            # Compose 화면 (Home, Settings)
│       ├── service/       # ListenerService (foreground), WearMessageReceiver
│       ├── audio/         # AudioPlayer (AudioTrack 래퍼)
│       └── notification/  # 알림 채널, 알림 빌더
├── wear/            # 워치 앱
│   └── src/main/java/.../wear/
│       ├── ui/            # Compose for Wear OS 화면
│       ├── service/       # MonitorService (foreground), ControlReceiver
│       └── audio/         # AudioCapture, LevelMeter(dB), Streamer
├── shared/          # 공용 상수, 데이터 모델, 경로 문자열
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
5. **포그라운드 서비스 타입**: 워치 `microphone`, 폰 `mediaPlayback` + `connectedDevice`. Android 14 이후 타입 명시 필수.
6. **화면 꺼짐 대응**: 워치 앱은 `WakeLock`(PARTIAL) + 포그라운드 서비스로 유지. Ambient 모드 진입해도 서비스는 살아있어야 함.

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
- [ ] `RECORD_AUDIO` 권한 요청 (워치 UI)
- [ ] `AudioCapture`: AudioRecord 16kHz mono PCM16, 코루틴 Flow로 버퍼 방출
- [ ] `LevelMeter`: RMS → dBFS, 100ms 단위 StateFlow
- [ ] 워치 화면에 실시간 레벨 바 표시
- **완료 조건**: 워치 화면에서 소리 크기에 따라 바가 움직임

### Phase 3 — 소음 알림
- [ ] 워치 `MonitorService` (foreground, type=microphone) 생성, 시작/정지
- [ ] 임계값 판정 + debounce/cooldown 로직 (`shared`에 순수 Kotlin으로, 단위 테스트 필수)
- [ ] `/alert` MessageClient 전송
- [ ] 폰 `WearableListenerService`로 수신 → 알림 채널 "소음 감지" 로 heads-up 알림
- [ ] 워치 화면 끈 채로 10분 동작 확인
- **완료 조건**: 워치 옆에서 박수 치면 다른 방의 폰이 울림

### Phase 4 — 라이브 오디오 스트리밍
- [ ] 워치 `Streamer`: ChannelClient.openChannel → OutputStream에 PCM 쓰기
- [ ] 폰 `ListenerService`: onChannelOpened → InputStream 읽어 AudioTrack 재생
- [ ] 폰 UI: "라이브 듣기" 토글, 지연/끊김 표시
- [ ] 폰 → 워치 `/control` START/STOP으로 스트리밍 제어
- **완료 조건**: 폰에서 워치 주변 소리가 2초 이내 지연으로 들림

### Phase 5 — 안정성 & 배터리
- [ ] 연결 끊김 감지 (CapabilityClient) → 양쪽 UI에 표시, 자동 재연결
- [ ] 워치 배터리 %를 `/status`로 전송, 20% 이하 시 폰 알림
- [ ] 1시간 연속 동작 배터리 소모 측정 → `docs/devlog/` 기록
- [ ] Doze / 앱 대기 버킷 영향 테스트
- **완료 조건**: 알림 모드 1시간 동작 시 워치 배터리 소모 15% 이하 (목표치, 측정 후 조정)

### Phase 6 — 마무리
- [ ] 설정 화면 (임계값 슬라이더, 쿨다운, 자동 스트리밍 여부)
- [ ] 소음 이벤트 히스토리 (Room)
- [ ] 앱 아이콘, 워치 타일(선택)

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
- 블루투스 실효 거리 ~10m, 벽 통과 시 급감. 같은 Wi-Fi면 Data Layer가 자동으로 Wi-Fi 사용.

---

## 9. 기록 (콘텐츠 재활용)

이 프로젝트는 나중에 블로그/영상 콘텐츠로 정리할 예정이므로 다음을 꾸준히 남긴다.

- `docs/devlog/YYYY-MM-DD.md`: 그날 한 일, 막힌 점, 해결 방법, 스크린샷 경로
- `docs/decisions/NNN-제목.md`: 왜 이 방식을 택했는지 (대안 포함)
- 측정 데이터(배터리, 지연 시간)는 표로 기록
- Phase 완료 시 짧은 데모 영상 촬영 (`docs/media/`)

Claude Code는 Phase 완료 시 devlog 초안을 자동으로 작성한다.
