package io.github.gumwoo.aiinterview.application.llm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 면접에 필요한 LLM 호출. DB 트랜잭션 밖에서 불러야 한다. 형식은 계획서 5.2, 5.3을 따른다.
 */
public interface InterviewLlm {

	/** 질문 하나와 채점 기준을 만든다. {@code context}는 사용량 로그에 남긴다. */
	GeneratedQuestion generateQuestion(QuestionRequest request, LlmCallContext context);

	/** 답변을 채점하고 다음 행동과 꼬리질문을 함께 받는다. */
	GradingResult gradeAnswer(GradingRequest request, LlmCallContext context);

	record QuestionRequest(String topic, String difficulty, List<String> recentConcepts) {
	}

	record GradingRequest(String question, List<String> expectedPoints, List<String> commonMistakes, String answer,
			boolean followUpAllowed) {
	}

	record GeneratedQuestion(
			@JsonPropertyDescription("개념 식별자. 영어 소문자와 하이픈만 사용. 예: k8s-cpu-request-limit") String conceptKey,
			@JsonPropertyDescription("면접관이 소리 내어 읽을 질문 하나") String question,
			@JsonPropertyDescription("답변에 들어가야 할 핵심 내용") List<String> expectedPoints,
			@JsonPropertyDescription("자주 하는 잘못된 설명") List<String> commonMistakes) {
	}

	record GradingResult(Verdict verdict,
			@JsonPropertyDescription("모델의 자기평가값. 0에서 1 사이") double confidence,
			@JsonPropertyDescription("답변에서 맞은 내용") List<String> correctPoints,
			@JsonPropertyDescription("답변에서 빠진 핵심 내용") List<String> missingPoints,
			@JsonPropertyDescription("답변에서 틀린 내용") List<String> incorrectPoints,
			@JsonPropertyDescription("면접관이 소리 내어 말할 피드백. 2~4문장") String feedbackToSpeak, NextAction nextAction,
			@JsonPropertyDescription("이어서 물을 질문 하나. 없으면 빈 문자열") String followUpQuestion,
			@JsonPropertyDescription("꼬리질문의 채점 기준. 꼬리질문이 없으면 빈 배열") List<String> followUpExpectedPoints) {
	}

	enum Verdict {

		CORRECT, PARTIAL, WRONG, UNDETERMINED

	}

	enum NextAction {

		FOLLOW_UP, EXPLAIN_AND_RECHECK, CLARIFY, NEXT_QUESTION

	}

}
