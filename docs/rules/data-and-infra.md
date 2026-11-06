# DB·Docker 규칙

## PostgreSQL

- 시각은 `timestamptz`, 문자열은 `text`, 상태 플래그는 `boolean`을 쓴다.
- 구조가 바뀔 수 있는 채점 근거와 피드백은 `jsonb`에 둔다. 검색·조인에 쓰는 값은 일반 컬럼으로 둔다.
- 인덱스는 실제 조회 조건에 맞춰 만든다. 복합 인덱스는 동등 조건 컬럼을 앞에 둔다.
- `SCHEDULED` 세션의 날짜 유니크는 부분 유니크 인덱스로 만든다.
- FK 컬럼에는 인덱스를 둔다. 스키마를 바꾼 뒤 아래 쿼리로 빠진 곳을 확인한다.
- `idle_in_transaction_session_timeout`과 `statement_timeout`을 설정해, 트랜잭션이 오래 열려 있으면 드러나게 한다.

```sql
SELECT conrelid::regclass, a.attname
FROM pg_constraint c
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
WHERE c.contype = 'f'
  AND NOT EXISTS (
    SELECT 1 FROM pg_index i
    WHERE i.indrelid = c.conrelid AND a.attnum = ANY(i.indkey)
  );
```

PK는 `bigint`(`GENERATED ALWAYS AS IDENTITY`)로 하고, 앱이 만드는 `client_request_id`만 `uuid`로 한다(계획서 8절).

## Flyway

- 적용된 마이그레이션 파일은 수정하지 않는다. 변경은 새 버전으로 추가한다.
- 혼자 쓰는 앱이라 데이터가 적으므로 `CREATE INDEX CONCURRENTLY`나 무중단 절차는 쓰지 않는다.

## Docker Compose

- 포트는 `127.0.0.1`에만 묶는다. 예: `127.0.0.1:18090:8080`. 외부 접근은 `tailscale serve`를 거친다.
- DB 포트는 필요한 경우에만 `127.0.0.1`로 연다. 백엔드는 Compose 네트워크로 접속한다.
- 이미지 태그를 고정한다. `latest`를 쓰지 않는다.
- 앱 컨테이너는 non-root로 실행하고 `no-new-privileges:true`를 설정한다.
- DB 데이터는 이름 있는 볼륨에 둔다.
- `restart: always`를 설정한다.
- 비밀값은 Git에서 제외한 `infra/.env`로 주입하고, 이미지 레이어와 Compose 파일에 넣지 않는다. 키 이름만 적은 `infra/.env.example`을 커밋한다.
- `.dockerignore`로 `.env`, `.git`, 빌드 산출물을 제외한다.

---

일부 내용은 [Everything Claude Code](https://github.com/affaan-m/everything-claude-code)(MIT, Copyright (c) 2026 Affaan Mustafa, 커밋 `b38be6a`)의 `postgres-patterns`, `docker-patterns`, `jpa-patterns`를 참고해 프로젝트에 맞게 다시 썼다. FK 점검 쿼리는 `postgres-patterns`에서 가져왔으며, 그 문서는 Supabase Agent Skills(MIT)를 근거로 한다.
