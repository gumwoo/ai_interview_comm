# 기준선

상용화를 시작하기 전 코드를 태그 `m0-baseline`(`d1f89c4`)으로 남겼다. 이 코드는 개인용 앱 [gumwoo/ai_interview](https://github.com/gumwoo/ai_interview)와 같고, 앱 ID·서버 포트·Docker 프로젝트 이름만 다르다.

- [API](api.md)
- [DB 스키마](schema.md)
- 결정과 바꿀 곳: [ADR 0001](../adr/0001-commercialization.md)

## 테스트 결과

2026-11-07에 이 PC(Windows 11, Docker Desktop)에서 기준선 코드로 실행한 결과다.

- 백엔드 `./gradlew test`: 101개 통과, 실패·건너뜀 0
- 앱 `./gradlew testDebugUnitTest assembleDebug lintDebug`: 단위 테스트 63개 통과, 빌드와 린트 통과
- CI(backend, android, gitleaks): PR #1에서 통과

실제 Claude를 부르는 `liveTest`와 `evalGrading`은 크레딧이 들어 기준선에서 돌리지 않았다. 채점 품질의 기존 측정값은 개인용 저장소 README의 채점 평가 부분에 있다.
