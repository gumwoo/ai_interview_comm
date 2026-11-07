package io.github.gumwoo.aiinterview.application.interview;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.gumwoo.aiinterview.application.llm.InterviewLlm;
import io.github.gumwoo.aiinterview.application.llm.LlmCallContext;
import io.github.gumwoo.aiinterview.domain.question.QuestionBankItem;
import io.github.gumwoo.aiinterview.domain.question.QuestionBankItem.QuestionOrigin;
import io.github.gumwoo.aiinterview.domain.question.QuestionBankItem.ReferencePoints;
import io.github.gumwoo.aiinterview.domain.session.SessionTurn;
import io.github.gumwoo.aiinterview.infrastructure.persistence.QuestionBankRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 다음 질문을 정한다. 복습 비율만큼 전에 틀린 개념을 먼저 다시 묻고, 그 밖에는 질문 은행을 쓴다.
 * 후보가 없을 때만 Claude로 생성해 저장한다.
 * 트랜잭션을 열지 않으므로 Claude를 기다리는 동안 DB 커넥션을 쥐지 않는다.
 */
@Component
public class QuestionPlanner {

	/** 아직 세션이 없을 때 쓰는, 어떤 턴과도 겹치지 않는 값이다. */
	static final long NO_SESSION = -1L;

	private final QuestionBankRepository repository;
	private final InterviewLlm llm;
	private final Clock clock;

	public QuestionPlanner(QuestionBankRepository repository, InterviewLlm llm, Clock clock) {
		this.repository = repository;
		this.llm = llm;
		this.clock = clock;
	}

	/** {@code requestId}는 이 질문을 정하게 만든 앱 요청이다. 생성 호출의 사용량 로그에 남긴다. */
	public PlannedQuestion next(List<String> topics, String difficulty, BigDecimal reviewRatio, long sessionId,
			UUID requestId, List<SessionTurn> turns) {
		if (isReviewTurn(reviewRatio, turns)) {
			Optional<QuestionBankItem> review = repository.pickReview(topics, difficulty, sessionId);
			if (review.isPresent()) {
				return new PlannedQuestion(review.get(), true);
			}
		}
		List<String> recentConcepts = turns.stream().map(SessionTurn::getConceptKey).distinct().toList();
		QuestionBankItem question = repository.pickNext(topics, difficulty, sessionId)
			.orElseGet(() -> generate(topics, difficulty, sessionId, requestId, recentConcepts));
		return new PlannedQuestion(question, false);
	}

	/** 지금까지 낸 복습 질문이 비율에 못 미치면 복습 차례다. 비율이 0보다 크면 첫 질문은 복습이다. */
	private static boolean isReviewTurn(BigDecimal reviewRatio, List<SessionTurn> turns) {
		List<SessionTurn> asked = turns.stream().filter((turn) -> turn.getFollowUpDepth() == 0).toList();
		long reviews = asked.stream().filter(SessionTurn::isReview).count();
		return BigDecimal.valueOf(reviews).compareTo(reviewRatio.multiply(BigDecimal.valueOf(asked.size() + 1L))) < 0;
	}

	private QuestionBankItem generate(List<String> topics, String difficulty, long sessionId, UUID requestId,
			List<String> recentConcepts) {
		String topic = topics.get(Math.floorMod(recentConcepts.size(), topics.size()));
		InterviewLlm.GeneratedQuestion generated = llm.generateQuestion(
				new InterviewLlm.QuestionRequest(topic, difficulty, recentConcepts),
				new LlmCallContext(sessionId == NO_SESSION ? null : sessionId, requestId));
		QuestionBankItem item = new QuestionBankItem(topic, generated.conceptKey(), difficulty, generated.question(),
				new ReferencePoints(generated.expectedPoints(), generated.commonMistakes()), QuestionOrigin.GENERATED,
				null, null, clock.instant());
		try {
			return repository.save(item);
		}
		catch (DataIntegrityViolationException ex) {
			return repository.findByQuestionText(generated.question()).orElseThrow(() -> ex);
		}
	}

	public record PlannedQuestion(QuestionBankItem question, boolean review) {
	}

}
