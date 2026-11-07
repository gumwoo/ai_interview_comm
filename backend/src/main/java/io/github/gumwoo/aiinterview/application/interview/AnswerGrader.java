package io.github.gumwoo.aiinterview.application.interview;

import java.util.List;

import io.github.gumwoo.aiinterview.application.llm.InterviewLlm;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.GradingRequest;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.GradingResult;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.NextAction;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.Verdict;
import io.github.gumwoo.aiinterview.application.llm.LlmCallContext;
import io.github.gumwoo.aiinterview.application.llm.LlmException;
import org.springframework.stereotype.Component;

/**
 * 답변을 채점한다. 모델이 답변을 거부하면 면접을 끊지 않고 판단보류로 처리한다.
 */
@Component
public class AnswerGrader {

	private static final String REFUSED_FEEDBACK = "이 답변은 평가하기 어렵습니다. 질문에 대한 내용으로 다시 말씀해 주시겠어요?";

	private final InterviewLlm llm;

	public AnswerGrader(InterviewLlm llm) {
		this.llm = llm;
	}

	public GradingResult grade(GradingRequest request, LlmCallContext context) {
		try {
			return llm.gradeAnswer(request, context);
		}
		catch (LlmException.Refused ex) {
			return new GradingResult(Verdict.UNDETERMINED, 0, List.of(), List.of(), List.of(), REFUSED_FEEDBACK,
					NextAction.CLARIFY, "", List.of());
		}
	}

}
