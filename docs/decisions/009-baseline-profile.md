# 009. Baseline profile: 설치 직후부터 미리 컴파일

- 날짜: 2026-10-01
- 상태: 채택 — 확인됨: 폰 감지기 화면 꺼짐 CPU JIT 2.6% → 프로필 1.9~2.0% (devlog 2026-10-01)

## 배경 (devlog 2026-10-01, ADR 008)
화면 꺼짐 감지기 모니터링 중 앱 CPU (코어 1개 기준):

| 빌드 | CPU |
|---|---|
| 릴리스, 설치 직후 (JIT) | 8.5% |
| 릴리스 + 미리 컴파일 (`cmd package compile -m speed`) | 6.9% |

1시간 10% 측정은 미리 컴파일한 상태였다. 실제 설치에서는 기기가 충전 중이고 쉬고 있을 때(백그라운드 dexopt)까지
JIT 로 돈다 → 첫날 밤은 측정보다 조금 더 쓸 수 있다.

## 결정
- `android-common` 에 `androidx.profileinstaller` 추가 (두 앱에 같이 들어감)
- `android-common/src/main/baseline-prof.txt` 에 규칙을 직접 적는다
  - 앱 코드 전체 `com/watchbabymonitor/**` (작다), 루프에서 쓰는 `kotlinx/coroutines/**`, `/status` 용 `kotlinx/serialization/json/**`
  - Compose 등은 라이브러리가 자기 프로필을 같이 싣고 온다
- 빌드하면 APK `assets/dexopt/baseline.prof` 에 들어간다
  - 워치: 우리 규칙으로 hot 메서드 31,836 → 39,186, 클래스 4,922 → 6,235 (바이너리를 풀어 확인)
- Play 설치는 설치할 때 바로 이 프로필로 컴파일, adb·APK 설치는 profileinstaller 가 프로필을 넣어 두고
  다음 dexopt 때 컴파일된다

## 대안
- **Macrobenchmark 로 프로필 생성 (정석)**: 실제로 실행한 경로를 기록해 더 정확하다. 하지만 워치 화면 자동 조작이 번거롭고,
  배터리 분석상 계속 도는 코드는 오디오 루프뿐이라 직접 적는 규칙으로 충분하다 → 보류
- **R8(축소·최적화)**: 효과는 있지만 kotlinx.serialization·Room 규칙 확인이 필요하다 → 따로 검토
