package io.github.gumwoo.aiinterview.domain.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "api_usage_log")
public class ApiUsageLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "session_id")
	private Long sessionId;

	@Column(name = "request_id")
	private UUID requestId;

	@Enumerated(EnumType.STRING)
	private Task task;

	@Enumerated(EnumType.STRING)
	private Outcome outcome;

	@Column(name = "model_id", nullable = false)
	private String modelId;

	@Column(name = "input_tokens", nullable = false)
	private int inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private int outputTokens;

	@Column(name = "cache_creation_input_tokens", nullable = false)
	private int cacheCreationInputTokens;

	@Column(name = "cache_read_input_tokens", nullable = false)
	private int cacheReadInputTokens;

	@Column(name = "estimated_cost_usd", nullable = false, precision = 12, scale = 6)
	private BigDecimal estimatedCostUsd;

	@Column(name = "latency_ms")
	private Integer latencyMs;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ApiUsageLog() {
	}

	public ApiUsageLog(Long sessionId, UUID requestId, Task task, Outcome outcome, String modelId, int inputTokens,
			int outputTokens, int cacheCreationInputTokens, int cacheReadInputTokens, BigDecimal estimatedCostUsd,
			Integer latencyMs, Instant createdAt) {
		this.sessionId = sessionId;
		this.requestId = requestId;
		this.task = task;
		this.outcome = outcome;
		this.modelId = modelId;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.cacheCreationInputTokens = cacheCreationInputTokens;
		this.cacheReadInputTokens = cacheReadInputTokens;
		this.estimatedCostUsd = estimatedCostUsd;
		this.latencyMs = latencyMs;
		this.createdAt = createdAt;
	}

	public Long getSessionId() {
		return sessionId;
	}

	public UUID getRequestId() {
		return requestId;
	}

	public Task getTask() {
		return task;
	}

	public Outcome getOutcome() {
		return outcome;
	}

	public String getModelId() {
		return modelId;
	}

	public int getInputTokens() {
		return inputTokens;
	}

	public int getOutputTokens() {
		return outputTokens;
	}

	public int getCacheCreationInputTokens() {
		return cacheCreationInputTokens;
	}

	public int getCacheReadInputTokens() {
		return cacheReadInputTokens;
	}

	public BigDecimal getEstimatedCostUsd() {
		return estimatedCostUsd;
	}

	public Integer getLatencyMs() {
		return latencyMs;
	}

	/** 호출 목적. 꼬리질문은 채점 응답에 함께 오므로 따로 두지 않는다. */
	public enum Task {

		QUESTION_GENERATION, GRADING

	}

	/** 호출 결과. SUCCESS 밖의 값은 응답을 버렸거나 받지 못한 호출이다. */
	public enum Outcome {

		SUCCESS, REFUSED, INVALID_RESPONSE, UNAVAILABLE, REJECTED, ERROR

	}

}
