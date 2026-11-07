package io.github.gumwoo.aiinterview.application.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import io.github.gumwoo.aiinterview.TestcontainersConfiguration;
import io.github.gumwoo.aiinterview.application.llm.LlmCallContext;
import io.github.gumwoo.aiinterview.application.llm.LlmException;
import io.github.gumwoo.aiinterview.domain.usage.ApiUsageLog;
import io.github.gumwoo.aiinterview.domain.usage.ApiUsageLog.Outcome;
import io.github.gumwoo.aiinterview.domain.usage.ApiUsageLog.Task;
import io.github.gumwoo.aiinterview.infrastructure.claude.ClaudeProperties;
import io.github.gumwoo.aiinterview.infrastructure.persistence.ApiUsageLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = { "app.api-token=" + TestcontainersConfiguration.TEST_API_TOKEN,
		"app.claude.api-key=test-key" })
@Import(TestcontainersConfiguration.class)
class ApiUsageServiceTest {

	private static final String MODEL = "claude-haiku-5-5";

	/** 한국 시간 2026-11-03 00:10 */
	private static final Instant NOW = Instant.parse("2026-11-02T15:10:00Z");

	@Autowired
	private ApiUsageLogRepository repository;

	private ApiUsageService service;

	@BeforeEach
	void setUp() {
		repository.deleteAll();
		ClaudeProperties properties = new ClaudeProperties("key", null, MODEL, "medium", Duration.ofSeconds(20), 2, 4000,
				1000, 500, Map.of(MODEL, new ClaudeProperties.ModelPrice(new BigDecimal("0.10"), new BigDecimal("0.50"))));
		service = new ApiUsageService(repository, properties, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
	}

	@Test
	void should_record_tokens_and_estimated_cost() {
		UUID requestId = UUID.randomUUID();
		service.record(Task.QUESTION_GENERATION, new LlmCallContext(null, requestId), Outcome.SUCCESS,
				new TokenUsage(1000, 2000, 30, 40), 1234);

		ApiUsageLog log = repository.findAll().get(0);
		assertThat(log.getModelId()).isEqualTo(MODEL);
		assertThat(log.getTask()).isEqualTo(Task.QUESTION_GENERATION);
		assertThat(log.getOutcome()).isEqualTo(Outcome.SUCCESS);
		assertThat(log.getSessionId()).isNull();
		assertThat(log.getRequestId()).isEqualTo(requestId);
		assertThat(log.getInputTokens()).isEqualTo(1000);
		assertThat(log.getOutputTokens()).isEqualTo(2000);
		assertThat(log.getCacheCreationInputTokens()).isEqualTo(30);
		assertThat(log.getCacheReadInputTokens()).isEqualTo(40);
		assertThat(log.getLatencyMs()).isEqualTo(1234);
		assertThat(log.getEstimatedCostUsd()).isEqualByComparingTo("0.001100");
	}

	@Test
	void should_record_failed_call_without_tokens() {
		service.record(Task.GRADING, LlmCallContext.NONE, Outcome.UNAVAILABLE, TokenUsage.NONE, 30_000);

		ApiUsageLog log = repository.findAll().get(0);
		assertThat(log.getOutcome()).isEqualTo(Outcome.UNAVAILABLE);
		assertThat(log.getInputTokens()).isZero();
		assertThat(log.getEstimatedCostUsd()).isEqualByComparingTo("0");
	}

	@Test
	void should_store_every_task_and_outcome() {
		for (Task task : Task.values()) {
			for (Outcome outcome : Outcome.values()) {
				service.record(task, LlmCallContext.NONE, outcome, TokenUsage.NONE, 0);
			}
		}

		assertThat(repository.count()).isEqualTo((long) Task.values().length * Outcome.values().length);
	}

	@Test
	void should_allow_call_below_limit() {
		record(999, 499);

		assertThatCode(service::ensureWithinDailyLimit).doesNotThrowAnyException();
	}

	@Test
	void should_block_call_when_input_limit_reached() {
		record(1000, 0);

		assertThatThrownBy(service::ensureWithinDailyLimit).isInstanceOf(LlmException.QuotaExceeded.class);
	}

	@Test
	void should_block_call_when_output_limit_reached() {
		record(0, 500);

		assertThatThrownBy(service::ensureWithinDailyLimit).isInstanceOf(LlmException.QuotaExceeded.class);
	}

	@Test
	void should_not_count_usage_before_midnight_in_app_zone() {
		saveAt(NOW.minus(Duration.ofMinutes(11)));

		assertThatCode(service::ensureWithinDailyLimit).doesNotThrowAnyException();
	}

	@Test
	void should_count_usage_after_midnight_in_app_zone() {
		saveAt(NOW.minus(Duration.ofMinutes(9)));

		assertThatThrownBy(service::ensureWithinDailyLimit).isInstanceOf(LlmException.QuotaExceeded.class);
	}

	private void record(long inputTokens, long outputTokens) {
		service.record(Task.GRADING, LlmCallContext.NONE, Outcome.SUCCESS, new TokenUsage(inputTokens, outputTokens, 0, 0),
				0);
	}

	private void saveAt(Instant createdAt) {
		repository.save(new ApiUsageLog(null, null, Task.GRADING, Outcome.SUCCESS, MODEL, 5000, 5000, 0, 0,
				BigDecimal.ONE, 0, createdAt));
	}

}
