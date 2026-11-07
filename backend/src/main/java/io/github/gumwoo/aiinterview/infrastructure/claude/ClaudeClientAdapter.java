package io.github.gumwoo.aiinterview.infrastructure.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.Usage;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm;
import io.github.gumwoo.aiinterview.application.llm.LlmCallContext;
import io.github.gumwoo.aiinterview.application.llm.LlmException;
import io.github.gumwoo.aiinterview.application.usage.ApiUsageService;
import io.github.gumwoo.aiinterview.application.usage.TokenUsage;
import io.github.gumwoo.aiinterview.domain.usage.ApiUsageLog.Outcome;
import io.github.gumwoo.aiinterview.domain.usage.ApiUsageLog.Task;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Claude Messages API 호출을 맡는다. 재시도는 SDK 설정을 쓰고, 여기서 다시 재시도하지 않는다.
 */
@Component
public class ClaudeClientAdapter implements InterviewLlm {

	private final AnthropicClient client;
	private final ClaudeProperties properties;
	private final ApiUsageService usageService;
	private final String questionSystemPrompt;
	private final String gradingSystemPrompt;

	public ClaudeClientAdapter(AnthropicClient client, ClaudeProperties properties, ApiUsageService usageService) {
		this.client = client;
		this.properties = properties;
		this.usageService = usageService;
		this.questionSystemPrompt = loadPrompt("prompts/question-generator.md");
		this.gradingSystemPrompt = loadPrompt("prompts/grading.md");
	}

	@Override
	public GeneratedQuestion generateQuestion(QuestionRequest request, LlmCallContext context) {
		String userMessage = """
				<topic>%s</topic>
				<difficulty>%s</difficulty>
				<recent_concepts>%s</recent_concepts>
				""".formatted(request.topic(), request.difficulty(), String.join(", ", request.recentConcepts()));
		return call(Task.QUESTION_GENERATION, questionSystemPrompt, userMessage, GeneratedQuestion.class, context,
				ClaudeClientAdapter::validateQuestion);
	}

	@Override
	public GradingResult gradeAnswer(GradingRequest request, LlmCallContext context) {
		String userMessage = """
				<question>%s</question>
				<expected_points>%s</expected_points>
				<common_mistakes>%s</common_mistakes>
				<follow_up_allowed>%s</follow_up_allowed>
				<answer>%s</answer>
				""".formatted(request.question(), String.join("\n", request.expectedPoints()),
				String.join("\n", request.commonMistakes()), request.followUpAllowed(), request.answer());
		return call(Task.GRADING, gradingSystemPrompt, userMessage, GradingResult.class, context,
				ClaudeClientAdapter::validateGrading);
	}

	private static void validateQuestion(GeneratedQuestion question) {
		requireText(question.conceptKey(), "conceptKey");
		requireText(question.question(), "question");
		requireNotEmpty(question.expectedPoints(), "expectedPoints");
	}

	private static void validateGrading(GradingResult result) {
		requireText(result.feedbackToSpeak(), "feedbackToSpeak");
		if (result.verdict() == null || result.nextAction() == null) {
			throw invalid("verdict와 nextAction이 필요합니다");
		}
		if (result.nextAction() == NextAction.FOLLOW_UP) {
			requireText(result.followUpQuestion(), "followUpQuestion");
			requireNotEmpty(result.followUpExpectedPoints(), "followUpExpectedPoints");
		}
	}

	/**
	 * 호출 한 번을 성공 여부와 관계없이 사용량 로그에 남긴다. 상한에 걸려 호출하지 않았으면 남기지 않는다.
	 * 지연 시간은 SDK 재시도를 포함한 전체 시간이다.
	 */
	private <T> T call(Task task, String systemPrompt, String userMessage, Class<T> responseType,
			LlmCallContext context, Consumer<T> validator) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Claude 호출은 DB 트랜잭션 밖에서 해야 합니다");
		}
		usageService.ensureWithinDailyLimit();

		StructuredMessageCreateParams<T> params = buildParams(systemPrompt, userMessage, responseType);
		long startedAt = System.nanoTime();
		TokenUsage tokens = TokenUsage.NONE;
		T result;
		try {
			StructuredMessage<T> response = send(params);
			tokens = tokens(response.usage());
			result = read(response);
			validator.accept(result);
		}
		catch (RuntimeException ex) {
			recordFailure(task, context, ex, tokens, elapsedMs(startedAt));
			throw ex;
		}
		usageService.record(task, context, Outcome.SUCCESS, tokens, elapsedMs(startedAt));
		return result;
	}

	/** 기록이 실패해도 원래 실패를 가리지 않는다. */
	private void recordFailure(Task task, LlmCallContext context, RuntimeException failure, TokenUsage tokens,
			long latencyMs) {
		try {
			usageService.record(task, context, outcome(failure), tokens, latencyMs);
		}
		catch (RuntimeException ex) {
			failure.addSuppressed(ex);
		}
	}

	private static Outcome outcome(RuntimeException failure) {
		if (failure instanceof LlmException.Refused) {
			return Outcome.REFUSED;
		}
		if (failure instanceof LlmException.InvalidResponse) {
			return Outcome.INVALID_RESPONSE;
		}
		if (failure instanceof LlmException.Unavailable) {
			return Outcome.UNAVAILABLE;
		}
		if (failure instanceof LlmException.Rejected) {
			return Outcome.REJECTED;
		}
		return Outcome.ERROR;
	}

	private static TokenUsage tokens(Usage usage) {
		return new TokenUsage(usage.inputTokens(), usage.outputTokens(), usage.cacheCreationInputTokens().orElse(0L),
				usage.cacheReadInputTokens().orElse(0L));
	}

	private static long elapsedMs(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	private static <T> T read(StructuredMessage<T> response) {
		StopReason stopReason = response.stopReason().orElse(null);
		if (StopReason.REFUSAL.equals(stopReason)) {
			throw new LlmException.Refused("모델이 답변을 거부했습니다");
		}
		if (!StopReason.END_TURN.equals(stopReason)) {
			throw invalid("응답이 정상 종료되지 않았습니다: " + stopReason);
		}
		return extract(response);
	}

	private <T> StructuredMessageCreateParams<T> buildParams(String systemPrompt, String userMessage,
			Class<T> responseType) {
		StructuredOutputConfig<T> outputConfig = StructuredOutputConfig.<T>builder()
			.format(responseType)
			.effort(OutputConfig.Effort.of(properties.effort()))
			.build();
		return MessageCreateParams.builder()
			.model(properties.model())
			.maxTokens(properties.maxOutputTokens())
			.system(systemPrompt)
			.addUserMessage(userMessage)
			.outputConfig(outputConfig)
			.build();
	}

	private <T> StructuredMessage<T> send(StructuredMessageCreateParams<T> params) {
		try {
			return client.messages().create(params);
		}
		catch (RateLimitException | InternalServerException | AnthropicIoException | AnthropicRetryableException ex) {
			throw new LlmException.Unavailable("Claude API를 일시적으로 사용할 수 없습니다", ex);
		}
		catch (AnthropicServiceException ex) {
			throw new LlmException.Rejected("Claude API가 요청을 거부했습니다: HTTP " + ex.statusCode(), ex);
		}
	}

	private static <T> T extract(StructuredMessage<T> response) {
		try {
			return response.content()
				.stream()
				.map(StructuredContentBlock::text)
				.flatMap(Optional::stream)
				.map(StructuredTextBlock::text)
				.findFirst()
				.orElseThrow(() -> invalid("응답에 본문이 없습니다"));
		}
		catch (AnthropicInvalidDataException ex) {
			throw new LlmException.InvalidResponse("응답이 스키마에 맞지 않습니다", ex);
		}
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw invalid(field + " 값이 비어 있습니다");
		}
	}

	private static void requireNotEmpty(List<String> values, String field) {
		if (values == null || values.isEmpty()) {
			throw invalid(field + " 값이 비어 있습니다");
		}
	}

	private static LlmException.InvalidResponse invalid(String message) {
		return new LlmException.InvalidResponse(message, null);
	}

	private static String loadPrompt(String path) {
		try {
			return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("프롬프트를 읽을 수 없습니다: " + path, ex);
		}
	}

}
