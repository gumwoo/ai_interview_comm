package io.github.gumwoo.aiinterview.application.interview;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.github.gumwoo.aiinterview.application.interview.FollowUpGenerator.FollowUp;
import io.github.gumwoo.aiinterview.application.interview.InterviewException.ErrorCode;
import io.github.gumwoo.aiinterview.application.interview.InterviewModels.AnswerView;
import io.github.gumwoo.aiinterview.application.interview.InterviewModels.HintView;
import io.github.gumwoo.aiinterview.application.interview.InterviewModels.HistoryItem;
import io.github.gumwoo.aiinterview.application.interview.InterviewModels.SessionView;
import io.github.gumwoo.aiinterview.application.interview.InterviewModels.StartSession;
import io.github.gumwoo.aiinterview.application.interview.InterviewModels.SubmitAnswer;
import io.github.gumwoo.aiinterview.application.interview.QuestionPlanner.PlannedQuestion;
import io.github.gumwoo.aiinterview.application.interview.InterviewModels.SummaryView;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.GradingRequest;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.GradingResult;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.Verdict;
import io.github.gumwoo.aiinterview.application.llm.LlmCallContext;
import io.github.gumwoo.aiinterview.application.llm.LlmException;
import io.github.gumwoo.aiinterview.domain.session.InterviewSession;
import io.github.gumwoo.aiinterview.domain.session.SessionTurn;
import io.github.gumwoo.aiinterview.domain.session.SessionTurn.ProcessingStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 면접 진행을 맡는다. 트랜잭션을 열지 않고, 저장은 {@link InterviewStore}의 짧은 트랜잭션으로 나눈다.
 * 순서는 답변 저장, 채점, 결과 저장, 다음 턴 생성이다. 다음 턴 생성이 실패해도 채점 결과는 남고,
 * 같은 요청을 다시 보내면 채점 없이 다음 턴만 만든다.
 */
@Service
public class InterviewOrchestrator {

	private final InterviewStore store;
	private final QuestionPlanner planner;
	private final AnswerGrader grader;
	private final FollowUpGenerator followUps;
	private final InterviewProperties properties;
	private final Clock clock;

	public InterviewOrchestrator(InterviewStore store, QuestionPlanner planner, AnswerGrader grader,
			FollowUpGenerator followUps, InterviewProperties properties, Clock clock) {
		this.store = store;
		this.planner = planner;
		this.grader = grader;
		this.followUps = followUps;
		this.properties = properties;
		this.clock = clock;
	}

	public SessionView start(StartSession command) {
		var existing = store.findByRequestId(command.clientRequestId());
		if (existing.isPresent()) {
			return view(existing.get());
		}
		PlannedQuestion question = planner.next(command.topics(), command.difficulty(), command.reviewRatio(),
				QuestionPlanner.NO_SESSION, command.clientRequestId(), List.of());
		try {
			return view(store.createSession(command, question));
		}
		catch (DataIntegrityViolationException ex) {
			return store.findByRequestId(command.clientRequestId())
				.map(this::view)
				.orElseThrow(() -> new InterviewException(ErrorCode.SCHEDULED_SESSION_EXISTS, "오늘의 예약 면접은 이미 시작했습니다"));
		}
	}

	public SessionView get(Long sessionId) {
		return view(store.getSession(sessionId));
	}

	public AnswerView submitAnswer(Long sessionId, SubmitAnswer command) {
		InterviewStore.Claim claim = claimAnswer(sessionId, command);
		SessionTurn turn = claim.turn();
		if (claim.needsGrading()) {
			grade(sessionId, turn);
			turn = store.getTurn(sessionId, turn.getId());
		}
		SessionTurn next = advance(sessionId, turn);
		return AnswerView.of(store.getSession(sessionId), turn, next);
	}

	private InterviewStore.Claim claimAnswer(Long sessionId, SubmitAnswer command) {
		try {
			return store.claim(sessionId, command);
		}
		catch (DataIntegrityViolationException ex) {
			throw new InterviewException(ErrorCode.REQUEST_ID_ALREADY_USED, "이미 사용한 요청 ID입니다");
		}
	}

	private void grade(Long sessionId, SessionTurn turn) {
		long startedAt = System.nanoTime();
		GradingResult result;
		try {
			result = grader.grade(new GradingRequest(turn.getQuestionText(), turn.getExpectedPoints(),
					turn.getCommonMistakes(), turn.getAnswerText(), followUps.canFollowUp(turn)),
					new LlmCallContext(sessionId, turn.getClientRequestId()));
		}
		catch (LlmException ex) {
			store.markFailed(sessionId, turn.getId());
			throw ex;
		}
		store.recordGrading(turn.getId(), result, (int) ((System.nanoTime() - startedAt) / 1_000_000));
	}

	/** 답변이 끝난 턴의 다음 턴을 돌려준다. 면접이 끝나면 null이다. */
	private SessionTurn advance(Long sessionId, SessionTurn answered) {
		var existingNext = store.findTurn(sessionId, answered.getTurnNo() + 1);
		if (existingNext.isPresent()) {
			return existingNext.get();
		}
		InterviewSession session = store.getSession(sessionId);
		if (session.isCompleted()) {
			return null;
		}
		if (answered.getTurnNo() >= properties.maxSessionTurns() || session.isTimeOver(clock.instant())) {
			store.complete(sessionId);
			return null;
		}
		return store.addTurn(sessionId, nextTurn(session, answered));
	}

	private SessionTurn nextTurn(InterviewSession session, SessionTurn answered) {
		int nextTurnNo = answered.getTurnNo() + 1;
		FollowUp followUp = followUps.decide(answered, answered.getFeedback());
		if (followUp != null) {
			return new SessionTurn(session.getId(), nextTurnNo, null, answered.getConceptKey(), followUp.questionText(),
					followUp.expectedPoints(), followUp.commonMistakes(), answered.getFollowUpDepth() + 1,
					clock.instant());
		}
		PlannedQuestion question = planner.next(session.getTopics(), session.getDifficulty(), session.getReviewRatio(),
				session.getId(), answered.getClientRequestId(), store.getTurns(session.getId()));
		return InterviewStore.newTurn(session.getId(), nextTurnNo, question, clock.instant());
	}

	public HintView hint(Long sessionId, Long turnId) {
		SessionTurn turn = store.getTurn(sessionId, turnId);
		if (turn.getProcessingStatus() == ProcessingStatus.GRADED) {
			throw new InterviewException(ErrorCode.TURN_ALREADY_ANSWERED, "이미 답변한 질문입니다");
		}
		return new HintView(turn.getId(), "이 내용부터 떠올려 보세요. " + turn.getExpectedPoints().get(0));
	}

	public SummaryView end(Long sessionId) {
		store.complete(sessionId);
		return summary(sessionId);
	}

	/** 요약은 Claude를 부르지 않고 저장된 판정으로 만든다. */
	public SummaryView summary(Long sessionId) {
		InterviewSession session = store.getSession(sessionId);
		List<SessionTurn> graded = store.getTurns(sessionId)
			.stream()
			.filter((turn) -> turn.getProcessingStatus() == ProcessingStatus.GRADED)
			.toList();
		Map<Verdict, Long> counts = new EnumMap<>(Verdict.class);
		for (Verdict verdict : Verdict.values()) {
			counts.put(verdict, graded.stream().filter((turn) -> turn.getVerdict() == verdict).count());
		}
		return new SummaryView(session.getId(), session.getStatus(), graded.size(), counts,
				graded.stream().flatMap((turn) -> turn.getFeedback().missingPoints().stream()).distinct().toList(),
				graded.stream().flatMap((turn) -> turn.getFeedback().incorrectPoints().stream()).distinct().toList());
	}

	public List<HistoryItem> history(LocalDate from, LocalDate to) {
		return store.history(from, to);
	}

	public void delete(Long sessionId) {
		store.delete(sessionId);
	}

	private SessionView view(InterviewSession session) {
		return SessionView.of(session, store.getTurns(session.getId()));
	}

}
