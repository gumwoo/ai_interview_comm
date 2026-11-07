package io.github.gumwoo.aiinterview.application.interview;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.gumwoo.aiinterview.application.interview.InterviewException.ErrorCode;
import io.github.gumwoo.aiinterview.application.interview.InterviewModels.HistoryItem;
import io.github.gumwoo.aiinterview.application.interview.InterviewModels.StartSession;
import io.github.gumwoo.aiinterview.application.interview.InterviewModels.SubmitAnswer;
import io.github.gumwoo.aiinterview.application.interview.QuestionPlanner.PlannedQuestion;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.GradingResult;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.Verdict;
import io.github.gumwoo.aiinterview.domain.question.QuestionBankItem;
import io.github.gumwoo.aiinterview.domain.session.InterviewSession;
import io.github.gumwoo.aiinterview.domain.session.SessionTurn;
import io.github.gumwoo.aiinterview.domain.session.SessionTurn.ProcessingStatus;
import io.github.gumwoo.aiinterview.infrastructure.persistence.ApiUsageLogRepository;
import io.github.gumwoo.aiinterview.infrastructure.persistence.InterviewSessionRepository;
import io.github.gumwoo.aiinterview.infrastructure.persistence.SessionTurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 면접 상태를 DB에 저장하는 짧은 트랜잭션들. Claude 호출은 이 클래스 밖에서 한다.
 */
@Service
@Transactional(readOnly = true)
public class InterviewStore {

	private final InterviewSessionRepository sessions;
	private final SessionTurnRepository turns;
	private final ReviewScheduler reviews;
	private final ApiUsageLogRepository usageLogs;
	private final InterviewProperties properties;
	private final Clock clock;

	public InterviewStore(InterviewSessionRepository sessions, SessionTurnRepository turns, ReviewScheduler reviews,
			ApiUsageLogRepository usageLogs, InterviewProperties properties, Clock clock) {
		this.sessions = sessions;
		this.turns = turns;
		this.reviews = reviews;
		this.usageLogs = usageLogs;
		this.properties = properties;
		this.clock = clock;
	}

	public Optional<InterviewSession> findByRequestId(UUID requestId) {
		return sessions.findByClientRequestId(requestId);
	}

	public InterviewSession getSession(Long sessionId) {
		return sessions.findById(sessionId)
			.orElseThrow(() -> new InterviewException(ErrorCode.SESSION_NOT_FOUND, "세션을 찾을 수 없습니다"));
	}

	public List<SessionTurn> getTurns(Long sessionId) {
		return turns.findBySessionIdOrderByTurnNo(sessionId);
	}

	public Optional<SessionTurn> findTurn(Long sessionId, int turnNo) {
		return turns.findBySessionIdAndTurnNo(sessionId, turnNo);
	}

	public SessionTurn getTurn(Long sessionId, Long turnId) {
		return turns.findByIdAndSessionId(turnId, sessionId)
			.orElseThrow(() -> new InterviewException(ErrorCode.TURN_NOT_FOUND, "질문을 찾을 수 없습니다"));
	}

	/** 기간 안의 면접을 최근 순으로 돌려준다. 답변이 하나도 없는 면접은 볼 내용이 없어 뺀다. */
	public List<HistoryItem> history(LocalDate from, LocalDate to) {
		List<InterviewSession> found = sessions.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDesc(
				from.atStartOfDay(clock.getZone()).toInstant(), to.plusDays(1).atStartOfDay(clock.getZone()).toInstant());
		if (found.isEmpty()) {
			return List.of();
		}
		Map<Long, Map<Verdict, Long>> counts = new HashMap<>();
		turns.countVerdicts(found.stream().map(InterviewSession::getId).toList(), ProcessingStatus.GRADED)
			.forEach((row) -> counts.computeIfAbsent(row.getSessionId(), (id) -> emptyCounts())
				.put(row.getVerdict(), row.getTotal()));
		return found.stream()
			.filter((session) -> counts.containsKey(session.getId()))
			.map((session) -> {
				Map<Verdict, Long> verdicts = counts.get(session.getId());
				return new HistoryItem(session.getId(), session.getSource(), session.getStartedAt(),
						session.getEndedAt(), verdicts.values().stream().mapToLong(Long::longValue).sum(), verdicts);
			})
			.toList();
	}

	private static Map<Verdict, Long> emptyCounts() {
		Map<Verdict, Long> counts = new EnumMap<>(Verdict.class);
		for (Verdict verdict : Verdict.values()) {
			counts.put(verdict, 0L);
		}
		return counts;
	}

	/** 세션과 첫 턴을 만들고, 세션이 없을 때 부른 첫 질문 생성 호출의 사용량 로그를 세션에 붙인다. */
	@Transactional
	public InterviewSession createSession(StartSession command, PlannedQuestion question) {
		InterviewSession session = sessions.saveAndFlush(new InterviewSession(command.clientRequestId(),
				command.scheduledDate(), command.source(), command.topics(), command.difficulty(),
				command.durationMinutes(), command.reviewRatio(), clock.instant()));
		turns.save(newTurn(session.getId(), 1, question, clock.instant()));
		usageLogs.attachToSession(command.clientRequestId(), session.getId());
		return session;
	}

	/**
	 * 답변을 먼저 저장하고 채점 권한을 얻는다. 이미 채점이 끝난 재전송이면 그 턴을 그대로 돌려준다.
	 */
	@Transactional
	public Claim claim(Long sessionId, SubmitAnswer command) {
		InterviewSession session = getSession(sessionId);
		SessionTurn turn = getTurn(sessionId, command.turnId());
		boolean sameRequest = command.clientRequestId().equals(turn.getClientRequestId());
		if (sameRequest && turn.getProcessingStatus() == ProcessingStatus.GRADED) {
			return new Claim(turn, false);
		}
		if (session.isCompleted()) {
			throw new InterviewException(ErrorCode.SESSION_COMPLETED, "이미 끝난 면접입니다");
		}
		if (turn.getProcessingStatus() == ProcessingStatus.GRADED) {
			throw new InterviewException(ErrorCode.TURN_ALREADY_ANSWERED, "이미 답변한 질문입니다");
		}
		if (!sameRequest && turns.existsBySessionIdAndClientRequestId(sessionId, command.clientRequestId())) {
			throw new InterviewException(ErrorCode.REQUEST_ID_ALREADY_USED, "이미 사용한 요청 ID입니다");
		}
		int claimed = turns.claimForGrading(turn.getId(), command.recognizedText(), command.clientRequestId());
		if (claimed == 0) {
			throw new InterviewException(ErrorCode.GRADING_IN_PROGRESS, "채점이 진행 중입니다");
		}
		getSession(sessionId).markGrading();
		return new Claim(getTurn(sessionId, command.turnId()), true);
	}

	@Transactional
	public void recordGrading(Long turnId, GradingResult result, int responseMs) {
		SessionTurn turn = turns.findById(turnId).orElseThrow();
		turn.recordGrading(result, responseMs);
		reviews.record(turn);
	}

	@Transactional
	public void markFailed(Long sessionId, Long turnId) {
		turns.findById(turnId).orElseThrow().markFailed();
		getSession(sessionId).markAsking();
	}

	/** 다음 턴을 만든다. 재전송과 겹쳐 이미 있으면 있는 턴을 쓴다. */
	@Transactional
	public SessionTurn addTurn(Long sessionId, SessionTurn turn) {
		Optional<SessionTurn> existing = findTurn(sessionId, turn.getTurnNo());
		if (existing.isPresent()) {
			return existing.get();
		}
		SessionTurn saved = turns.save(turn);
		getSession(sessionId).markAsking();
		return saved;
	}

	@Transactional
	public InterviewSession complete(Long sessionId) {
		InterviewSession session = getSession(sessionId);
		session.complete(clock.instant());
		return session;
	}

	@Transactional
	public void delete(Long sessionId) {
		sessions.delete(getSession(sessionId));
	}

	@Transactional
	public int failInterruptedGradings() {
		return turns.failInterruptedGradings();
	}

	static SessionTurn newTurn(Long sessionId, int turnNo, PlannedQuestion planned, Instant now) {
		QuestionBankItem question = planned.question();
		return new SessionTurn(sessionId, turnNo, question.getId(), question.getConceptKey(), question.getQuestionText(),
				question.getReferencePoints().expectedPoints(), question.getReferencePoints().commonMistakes(), 0,
				planned.review(), now);
	}

	public record Claim(SessionTurn turn, boolean needsGrading) {
	}

}
