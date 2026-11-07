# DB 스키마 기준선

태그 `m0-baseline` 시점의 스키마다. 원본은 `backend/src/main/resources/db/migration/`의 V1~V3이고, 아래는 세 마이그레이션을 모두 적용한 결과다. 모든 PK는 `bigint GENERATED ALWAYS AS IDENTITY`다.

## interview_session

면접 한 번.

- `client_request_id uuid NOT NULL UNIQUE`: 시작 요청의 재전송 구분
- `scheduled_date date NOT NULL`
- `source text`: `SCHEDULED`, `MANUAL`
- `status text`: `ASKING`, `LISTENING`, `GRADING`, `EXPLAINING`, `FOLLOW_UP`, `COMPLETED`
- `topic text` (V1에서 만들었고 지금 코드는 쓰지 않는다)
- `topics jsonb`, `difficulty text`, `duration_minutes integer`, `review_ratio numeric(3,2)` (V2, 시작할 때의 설정)
- `started_at timestamptz`, `ended_at timestamptz`

인덱스: `uq_interview_session_scheduled_date (scheduled_date) WHERE source = 'SCHEDULED'`

## session_turn

세션 안의 질문 하나와 그 답변.

- `session_id → interview_session ON DELETE CASCADE`
- `turn_no integer`, `question_id → question_bank` (꼬리질문은 null)
- `concept_key text`, `question_text text`, `expected_points_json jsonb`, `common_mistakes_json jsonb`
- `answer_text text`, `client_request_id uuid`
- `processing_status text`: `AWAITING_ANSWER`, `GRADING`, `GRADED`, `FAILED`
- `verdict text`: `CORRECT`, `PARTIAL`, `WRONG`, `UNDETERMINED`
- `feedback_json jsonb`, `response_ms integer`
- `follow_up_depth integer` (V2), `review boolean` (V3, 복습으로 나온 질문)
- `created_at timestamptz`

제약: `UNIQUE (session_id, turn_no)`, `UNIQUE (session_id, client_request_id)`

## question_bank

저장해 두고 다시 쓰는 질문.

- `topic`, `concept_key`, `difficulty`, `question_text`, `reference_points_json jsonb`
- `rubric_version integer`
- `origin text`: `SEED`, `GENERATED`
- `source_ref`, `source_commit`: 시드 질문의 근거 문서와 커밋
- `created_at`

인덱스: `(topic, difficulty)`, `(concept_key)`, `uq_question_bank_question_text (question_text)` (V2)

## review_item

다음 면접에서 다시 물을 개념.

- `concept_key text`, `source_turn_id → session_turn ON DELETE CASCADE`

제약: `uq_review_item_concept_key UNIQUE (concept_key)` (V3). V3에서 복습일·연속 정답 수·우선순위 열을 지우고, 기존 오답을 복습 대상으로 옮겼다.

## api_usage_log

Claude 호출 한 번의 사용량.

- `session_id → interview_session ON DELETE SET NULL`
- `model_id text`, `input_tokens integer`, `output_tokens integer`
- `estimated_cost_usd numeric(12,6)`: `application.yml`의 모델별 가격으로 계산한 추정값
- `created_at timestamptz`

인덱스: `(session_id)`, `(created_at)`

## 상용화에서 바꿀 점

- 사용자를 가리키는 열이 어느 테이블에도 없다.
- 다음 세 제약은 서비스 전체에서 유일하므로 여러 사용자가 쓰면 충돌한다: `uq_interview_session_scheduled_date`, `uq_question_bank_question_text`, `uq_review_item_concept_key`.
- `api_usage_log`에는 작업 종류, 성공 여부, 지연 시간, 캐시 토큰이 없다.

자세한 내용은 [ADR 0001](../adr/0001-commercialization.md)에 있다.
