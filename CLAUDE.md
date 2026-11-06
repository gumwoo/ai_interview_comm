# AI Voice Interview (상용화)

개인용 AI 음성 면접 앱 [gumwoo/ai_interview](https://github.com/gumwoo/ai_interview)를 일반 취업 준비생용 서비스로 바꾸는 저장소다.
지금 코드는 개인용 앱을 기록째 복사한 상태이고, 개인용 앱과 같은 폰·같은 PC에서 함께 돌 수 있게 앱 ID, 서버 포트, Docker 프로젝트 이름만 바꿨다.

## 문서

- 상용화 방향과 단계별 완료 기준: 상용화 마스터플랜 v1.0(2026-11-03). 아직 저장소에 넣지 않았으므로, 필요하면 사용자에게 받은 내용을 기준으로 한다.
- 기존 코드의 설계 원본: [AI_Voice_Interview_Development_Plan.md](AI_Voice_Interview_Development_Plan.md)
- 백엔드 규칙: [docs/rules/backend.md](docs/rules/backend.md)
- Android 규칙: [docs/rules/android.md](docs/rules/android.md)
- DB·Docker 규칙: [docs/rules/data-and-infra.md](docs/rules/data-and-infra.md)
- 채점 평가 규칙: [docs/rules/eval.md](docs/rules/eval.md)
- Git·이슈·PR 규칙: [docs/rules/git.md](docs/rules/git.md)

상용화 계획과 기존 계획서·규칙이 충돌하면 상용화 계획을 따르고, 충돌 내용을 사용자에게 알린다. 규칙 문서에 남은 1인 사용 전제는 그 부분을 바꾸는 단계에서 함께 고친다.

## 작업 방식

- 상용화 단계(M0~M6)는 사용자가 시작을 지시한 뒤에 진행한다. 계획서의 완료 조건을 확인해야 다음 단계로 넘어간다.
- 개인용 앱과 서버를 건드리지 않는다. 이 저장소의 앱 ID는 `io.github.gumwoo.aiinterview.comm`, 서버 포트는 18090, Docker 프로젝트 이름은 `ai-interview-comm`이다.
- 계획서의 초기 비목표(실제 전화 발신, 합격 예측, 실시간 끼어들기 음성, MSA·Kafka 운영 등)는 만들지 않는다.
- 한 PR에 무관한 리팩터링이나 대규모 패키지 이동을 섞지 않는다. API 계약이 바뀌면 앱 테스트도 함께 돌린다.

## 명령

- 백엔드 테스트(`backend/`에서 실행, Docker 필요): `./gradlew test`
- 실제 Claude 호출 테스트(`backend/`에서, 환경 변수 `ANTHROPIC_API_KEY` 필요, 크레딧 사용): `./gradlew liveTest`
- 채점 평가(`backend/`에서, 환경 변수 `ANTHROPIC_API_KEY` 필요, 크레딧 사용): `./gradlew evalGrading`
- 앱 단위 테스트와 빌드(`android/`에서 실행, Android SDK 37 필요): `./gradlew testDebugUnitTest assembleDebug lintDebug`
- 서버 실행(저장소 루트에서): `docker compose -f infra/compose.yaml up -d --build`
- 개발용 콘솔: `SPRING_PROFILES_ACTIVE=dev`로 서버를 띄운 뒤 `http://localhost:18090/dev/console`
- 상태 확인: `curl http://localhost:18090/api/v1/health`
- 로컬 설정: `infra/.env.example`을 `infra/.env`로 복사해 값을 채운다. 개인용 서버의 `.env`를 재사용하지 않는다.

## 항상 지킬 것

- **공개 저장소다.** `infra/.env`, `local.properties`, 앱 서명 키, `infra/backup/`, 로그, API 토큰, Tailscale 호스트 이름을 커밋하지 않는다. 비밀값이 한 번이라도 올라가면 폐기하고 새로 발급한다.
- **사용자 자료는 개인정보다.** 자기소개서, 이력서, 면접 기록 같은 실제 사용자 자료를 테스트 데이터, 로그, 스크린샷, 커밋에 넣지 않는다.
- **Claude API 키는 서버의 `infra/.env`에만 둔다.** APK와 채팅에 넣지 않는다.
- **Anthropic 크레딧 자동 충전을 쓰지 않는다.** 서버는 크레딧을 충전하지 않고, 호출 상한을 넘으면 오류를 안내한다. 사용자 결제는 계획서의 결제 단계에서 따로 다룬다.
- **Claude 호출은 DB 트랜잭션 밖에서 한다.** 답변을 먼저 저장하고, 같은 요청이 다시 와도 Claude를 또 호출하지 않는다.
- **모델은 `CLAUDE_MODEL` 설정값이다.** 채점과 꼬리질문 기본은 `claude-sonnet-5-5`다. 계획서의 작업별 모델 분리(자료 추출은 Haiku)는 평가셋으로 확인한 뒤 적용한다. 자동 테스트에서는 실제 API를 호출하지 않는다.
- **기존 시드 질문의 근거는 FlowTicket 문서다.** FlowTicket은 질문 출처로만 쓰고, 코드 규칙으로 가져오지 않는다.
