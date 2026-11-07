-- 면접 한 번의 비용과 실패 비용을 나눠 볼 수 있게 Claude 호출마다 작업 종류, 결과, 지연 시간을 남긴다.
-- 응답을 받지 못하고 실패한 호출도 토큰 0으로 기록한다. V4 이전 행의 task, outcome, latency_ms는 null이다.
ALTER TABLE api_usage_log
    ADD COLUMN task                        text CHECK (task IN ('QUESTION_GENERATION', 'GRADING')),
    ADD COLUMN outcome                     text CHECK (outcome IN ('SUCCESS', 'REFUSED', 'INVALID_RESPONSE', 'UNAVAILABLE', 'REJECTED', 'ERROR')),
    ADD COLUMN latency_ms                  integer,
    ADD COLUMN request_id                  uuid,
    ADD COLUMN cache_creation_input_tokens integer NOT NULL DEFAULT 0,
    ADD COLUMN cache_read_input_tokens     integer NOT NULL DEFAULT 0;

-- 세션을 만들기 전에 부른 첫 질문 생성 호출을 시작 요청 ID로 찾아 세션에 붙인다.
CREATE INDEX idx_api_usage_log_request_id ON api_usage_log (request_id);
