package io.github.gumwoo.aiinterview.application.usage;

/** Claude 응답의 토큰 수. 응답을 받지 못한 호출은 {@link #NONE}이다. */
public record TokenUsage(long inputTokens, long outputTokens, long cacheCreationInputTokens,
		long cacheReadInputTokens) {

	public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);

}
