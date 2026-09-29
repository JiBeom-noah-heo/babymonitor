# 001. 프로젝트 골격: 패키지명, 빌드 툴체인 버전

- 날짜: 2026-09-29
- 상태: 채택

## 결정

| 항목 | 값 |
|---|---|
| applicationId (mobile, wear 공통) | `com.watchbabymonitor` |
| namespace | `com.watchbabymonitor.mobile` / `.wear` / 패키지 `com.watchbabymonitor.shared` |
| Gradle | 8.11.1 (wrapper) |
| AGP | 8.7.3 |
| Kotlin | 2.1.10 (Compose 컴파일러 플러그인 포함) |
| Compose BOM | 2024.12.01 |
| Wear Compose | 1.4.0 |
| play-services-wearable | 19.0.0 |
| kotlinx-serialization / coroutines | 1.7.3 / 1.9.0 |
| compileSdk | 35 (mobile target 34, wear target 33 — CLAUDE.md 기준) |
| JVM target | 17 |

## 이유
- **applicationId 동일**: Data Layer 는 같은 패키지명 + 같은 서명 키끼리만 통신한다. namespace(R 클래스 패키지)는 모듈별로 분리해 충돌을 피한다.
- **AGP 8.7.3 / Kotlin 2.1.10**: 로컬 Gradle 캐시에 이미 있는 안정 조합. AGP 9.0 은 Kotlin 내장 등 구조 변경이 커서 MVP 동안은 8.x 유지.
- **shared 는 순수 Kotlin JVM 라이브러리**: 오디오/임계값 로직을 Android 없이 JUnit 으로 테스트하기 위함 (CLAUDE.md §6).
- 템플릿 마법사 대신 파일을 직접 작성했다. 결과물은 "Empty Activity" + "Empty Wear App" + "Kotlin Library" 템플릿과 동등.

## 대안
- AGP 9.x + Gradle 9.1: 최신이지만 마이그레이션 이슈 가능성 → 필요 시 별도 ADR.
- applicationId 를 `.mobile`/`.wear` 로 분리: Data Layer 통신 불가 → 기각.

## 변경 방법
- applicationId 를 바꾸려면 `mobile/build.gradle.kts`, `wear/build.gradle.kts` 두 곳을 **동시에** 바꾼다.
