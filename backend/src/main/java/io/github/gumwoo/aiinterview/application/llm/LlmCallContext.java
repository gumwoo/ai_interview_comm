package io.github.gumwoo.aiinterview.application.llm;

import java.util.UUID;

/**
 * Claude 호출을 일으킨 세션과 앱 요청. 사용량 로그에 남겨 면접 한 번의 비용과 재전송 비용을 나눠 본다.
 * 세션을 만들기 전의 호출은 {@code sessionId}가 null이고, 세션을 만들 때 {@code requestId}로 세션에 붙인다.
 */
public record LlmCallContext(Long sessionId, UUID requestId) {

	/** 면접 밖에서 부르는 호출(라이브 테스트, 채점 평가)에 쓴다. */
	public static final LlmCallContext NONE = new LlmCallContext(null, null);

}
