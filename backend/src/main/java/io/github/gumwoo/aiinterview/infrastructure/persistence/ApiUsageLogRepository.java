package io.github.gumwoo.aiinterview.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.gumwoo.aiinterview.domain.usage.ApiUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiUsageLogRepository extends JpaRepository<ApiUsageLog, Long> {

	@Query("""
			select new io.github.gumwoo.aiinterview.infrastructure.persistence.ApiUsageLogRepository$TokenTotals(
				coalesce(sum(u.inputTokens), 0L), coalesce(sum(u.outputTokens), 0L))
			from ApiUsageLog u
			where u.createdAt >= :from
			""")
	TokenTotals sumTokensSince(@Param("from") Instant from);

	/** 세션 없이 기록한 호출 중 이 요청으로 부른 것을 세션에 붙인다. */
	@Modifying
	@Query("update ApiUsageLog u set u.sessionId = :sessionId where u.requestId = :requestId and u.sessionId is null")
	int attachToSession(@Param("requestId") UUID requestId, @Param("sessionId") Long sessionId);

	record TokenTotals(long inputTokens, long outputTokens) {
	}

}
