# API 기준선

태그 `m0-baseline` 시점의 REST API다. 원본은 `backend/src/main/java/.../api/`의 컨트롤러와 `InterviewModels`다. 이후 API를 바꾸면 이 문서가 아니라 바뀐 계약 문서를 고치고, 이 문서는 비교 기준으로 남긴다.

## 공통

- 경로 앞부분은 `/api/v1`이다.
- `GET /api/v1/health`를 뺀 모든 요청은 `Authorization: Bearer <APP_API_TOKEN>`이 필요하다. 토큰이 없거나 틀리면 본문 없이 401이다. 토큰은 하나뿐이고, 통과하면 모두 같은 사용자(`owner`)로 처리한다.
- 오류 본문은 `{"code": "...", "message": "..."}`다. `message`는 사용자에게 보여도 되는 문장이다.
- 시각은 UTC ISO-8601(`Instant`), 날짜는 `yyyy-MM-dd`다.

## 상태 확인

`GET /api/v1/health`

- 인증 없이 호출한다. DB에 연결되면 200 `{"status": "UP"}`, 아니면 503 `{"status": "DOWN"}`이다.

## 면접 세션

### 시작

`POST /api/v1/sessions`

요청:

- `source`: `SCHEDULED` 또는 `MANUAL`
- `scheduledDate`: 날짜
- `topics`: 문자열 1~20개, 각 100자 이하
- `difficulty`: 50자 이하
- `durationMinutes`: 1~120
- `reviewRatio`: 0~1
- `clientRequestId`: UUID

응답은 `SessionView`다. 같은 `clientRequestId`로 다시 보내면 새로 만들지 않고 처음 만든 세션을 돌려준다. 같은 날 예약 면접(`SCHEDULED`)이 이미 있으면 409 `SCHEDULED_SESSION_EXISTS`다. 첫 질문을 고를 때 저장된 질문이 없으면 Claude로 생성하므로, 이 요청에서도 Claude를 호출할 수 있다.

### 조회와 삭제

- `GET /api/v1/sessions/{sessionId}`: `SessionView`
- `DELETE /api/v1/sessions/{sessionId}`: 204. 세션과 그 턴, 턴에서 나온 복습 항목을 함께 지운다. 사용량 로그는 남기고 세션 연결만 끊는다.

### 답변

`POST /api/v1/sessions/{sessionId}/answers`

요청:

- `turnId`: 답하는 턴
- `recognizedText`: 음성 인식 결과, 5000자 이하
- `clientRequestId`: UUID

답변을 먼저 저장하고 커밋한 뒤 트랜잭션 밖에서 Claude로 채점한다. 응답은 `AnswerView`다.

- 같은 `clientRequestId`로 다시 보냈고 채점이 끝났으면 Claude를 다시 부르지 않고 같은 결과를 돌려준다.
- 409 `SESSION_COMPLETED`: 끝난 면접
- 409 `TURN_ALREADY_ANSWERED`: 다른 요청으로 이미 채점이 끝난 턴
- 409 `REQUEST_ID_ALREADY_USED`: 같은 세션의 다른 턴에 쓴 요청 ID
- 409 `GRADING_IN_PROGRESS`: 같은 턴을 다른 요청이 채점하는 중
- 채점 호출이 실패하면 턴은 `FAILED`가 되고, 아래 Claude 오류 중 하나를 돌려준다.

### 힌트

`POST /api/v1/sessions/{sessionId}/hint`, 요청 `{"turnId": ...}`

- 응답 `HintView`. 저장된 기대 답변의 첫 항목으로 만들며 Claude를 부르지 않는다.
- 이미 채점된 턴이면 409 `TURN_ALREADY_ANSWERED`다.

### 종료와 요약

- `POST /api/v1/sessions/{sessionId}/end`: 면접을 끝내고 `SummaryView`를 돌려준다.
- `GET /api/v1/sessions/{sessionId}/summary`: `SummaryView`. 저장된 판정으로 만들며 Claude를 부르지 않는다.

## 기록

`GET /api/v1/history?from=yyyy-MM-dd&to=yyyy-MM-dd`

- 시작 시각이 서버 시간대 기준 `from` 0시 이상, `to` 다음 날 0시 미만인 세션을 최근 순으로 돌려준다.
- 채점된 답변이 하나도 없는 세션은 뺀다.
- 응답은 `HistoryItem` 배열이다.

## 응답 모델

- `SessionView`: `sessionId`, `status`, `turnId`, `questionText`, `durationLimitSeconds`, `startedAt`, `endedAt`, `turns`(`TurnView` 배열). 면접이 끝나면 `turnId`와 `questionText`는 null이다.
- `TurnView`: `turnId`, `turnNo`, `questionText`, `answerText`, `processingStatus`, `verdict`, `feedbackToSpeak`, `review`
- `AnswerView`: `sessionId`, `sessionStatus`, `turnId`, `verdict`, `feedbackToSpeak`, `nextAction`, `correctPoints`, `missingPoints`, `incorrectPoints`, `nextTurnId`, `nextQuestionText`. 면접이 끝났으면 `nextTurnId`와 `nextQuestionText`는 null이다.
- `HintView`: `turnId`, `hintText`
- `SummaryView`: `sessionId`, `status`, `answeredCount`, `verdictCounts`, `missingPoints`, `incorrectPoints`
- `HistoryItem`: `sessionId`, `source`, `startedAt`, `endedAt`, `answeredCount`, `verdictCounts`

값 목록:

- 세션 `status`: `ASKING`, `LISTENING`, `GRADING`, `EXPLAINING`, `FOLLOW_UP`, `COMPLETED`
- 턴 `processingStatus`: `AWAITING_ANSWER`, `GRADING`, `GRADED`, `FAILED`
- `verdict`: `CORRECT`, `PARTIAL`, `WRONG`, `UNDETERMINED`. `verdictCounts`는 네 값을 모두 키로 갖는다.
- `nextAction`: `FOLLOW_UP`, `EXPLAIN_AND_RECHECK`, `CLARIFY`, `NEXT_QUESTION`

## 오류 코드

- 400 `INVALID_REQUEST`: 본문이나 파라미터 형식 오류
- 404 `SESSION_NOT_FOUND`, `TURN_NOT_FOUND`
- 409: 위 세션·답변 항목에 적은 코드
- 429 `LLM_QUOTA_EXCEEDED`: 서비스 전체의 하루 Claude 토큰 상한 도달
- 503 `LLM_UNAVAILABLE`: 네트워크, 요청 한도, Claude 서버 오류처럼 다시 시도하면 풀릴 수 있는 실패
- 502 `LLM_REJECTED`: 인증, 요청 형식, 크레딧 부족처럼 재시도로 풀리지 않는 실패
- 502 `LLM_REFUSED`: 모델이 답변을 거부함. 채점에서는 오류 대신 `UNDETERMINED`로 처리하므로, 질문 생성에서만 나온다.
- 502 `LLM_INVALID_RESPONSE`: 응답이 잘렸거나 스키마에 맞지 않음

## 개발용 화면

`dev` 프로필에서만 `GET /dev/console`이 켜진다. 인증 없이 열리는 정적 화면이고, 화면에서 API를 부를 때는 입력한 토큰을 쓴다.
