package io.github.gumwoo.aiinterview.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import io.github.gumwoo.aiinterview.TestcontainersConfiguration;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.GradingRequest;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.GradingResult;
import io.github.gumwoo.aiinterview.application.llm.InterviewLlm.Verdict;
import io.github.gumwoo.aiinterview.application.llm.LlmCallContext;
import io.github.gumwoo.aiinterview.application.llm.LlmException;
import io.github.gumwoo.aiinterview.domain.usage.ApiUsageLog;
import io.github.gumwoo.aiinterview.eval.EvalCases.EvalCase;
import io.github.gumwoo.aiinterview.eval.EvalScorer.CaseRun;
import io.github.gumwoo.aiinterview.eval.EvalScorer.Criteria;
import io.github.gumwoo.aiinterview.eval.EvalScorer.Score;
import io.github.gumwoo.aiinterview.infrastructure.claude.ClaudeProperties;
import io.github.gumwoo.aiinterview.infrastructure.persistence.ApiUsageLogRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * 평가셋으로 채점 품질을 측정한다. 실제 API를 호출해 크레딧을 쓰며 {@code ./gradlew evalGrading}으로만 실행된다.
 * 규칙은 docs/rules/eval.md를 따른다. 판정 대조와 통과 기준 기록이 끝나야 실행된다.
 */
@Tag("eval")
@SpringBootTest(properties = { "app.api-token=" + TestcontainersConfiguration.TEST_API_TOKEN,
		"app.claude.daily-input-token-limit=100000000", "app.claude.daily-output-token-limit=100000000" })
@Import(TestcontainersConfiguration.class)
class GradingEvalRunner {

	private static final int RUNS_PER_CASE = 3;
	private static final Path CRITERIA_FILE = EvalCases.EVAL_DIR.resolve("pass-criteria.json");

	@Autowired
	private InterviewLlm llm;

	@Autowired
	private ClaudeProperties properties;

	@Autowired
	private ApiUsageLogRepository usageRepository;

	@Test
	void should_measure_grading_against_human_verdicts() throws Exception {
		EvalCases cases = EvalCases.load();
		assertThat(cases.verifiedAgainstRepository()).as("판정을 대조한 저장소 커밋을 verifiedAgainstRepository에 적어야 합니다").isNotBlank();
		assertThat(CRITERIA_FILE).as("통과 기준을 실행 전에 eval/pass-criteria.json에 적어야 합니다").exists();
		Criteria criteria = JsonMapper.builder().build().readValue(CRITERIA_FILE.toFile(), Criteria.class);

		usageRepository.deleteAll();
		List<CaseRun> runs = new ArrayList<>();
		List<Long> latencies = new ArrayList<>();
		StringBuilder details = new StringBuilder();
		for (EvalCase evalCase : cases.cases()) {
			runs.add(runCase(evalCase, latencies, details));
		}

		String report = report(runs, criteria, latencies, details);
		Path resultFile = EvalCases.EVAL_DIR.resolve("results")
			.resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + "-"
					+ properties.model() + "-" + properties.effort() + ".md");
		Files.createDirectories(resultFile.getParent());
		Files.writeString(resultFile, report);
		System.out.println("[eval] " + resultFile.toAbsolutePath().normalize());
		System.out.println(report);
	}

	private CaseRun runCase(EvalCase evalCase, List<Long> latencies, StringBuilder details) {
		GradingRequest request = new GradingRequest(evalCase.question(), evalCase.expectedPoints(),
				evalCase.commonMistakes(), evalCase.answer(), true);
		List<Verdict> verdicts = new ArrayList<>();
		String lastFeedback = "";
		for (int run = 0; run < RUNS_PER_CASE; run++) {
			long start = System.nanoTime();
			try {
				GradingResult result = llm.gradeAnswer(request, LlmCallContext.NONE);
				verdicts.add(result.verdict());
				lastFeedback = result.feedbackToSpeak() + " / 다음: " + result.nextAction() + " / 질문: "
						+ result.followUpQuestion();
			}
			catch (LlmException ex) {
				verdicts.add(null);
				lastFeedback = "실패: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
			}
			latencies.add((System.nanoTime() - start) / 1_000_000);
		}
		CaseRun caseRun = new CaseRun(evalCase.id(), evalCase.set(), evalCase.humanVerdict(), verdicts);
		details.append("| ")
			.append(evalCase.id())
			.append(" | ")
			.append(evalCase.set())
			.append(" | ")
			.append(evalCase.humanVerdict())
			.append(" | ")
			.append(verdicts)
			.append(" | ")
			.append(caseRun.majorityAgrees() ? "일치" : "**불일치**")
			.append(" | ")
			.append(lastFeedback.replace('|', '/').replace('\n', ' '))
			.append(" |\n");
		return caseRun;
	}

	private String report(List<CaseRun> runs, Criteria criteria, List<Long> latencies, StringBuilder details) {
		Score tune = EvalScorer.score(runs.stream().filter((run) -> run.set().equals("tune")).toList());
		Score holdout = EvalScorer.score(runs.stream().filter((run) -> run.set().equals("holdout")).toList());
		Score all = EvalScorer.score(runs);
		List<ApiUsageLog> usage = usageRepository.findAll();
		long input = usage.stream().mapToLong(ApiUsageLog::getInputTokens).sum();
		long output = usage.stream().mapToLong(ApiUsageLog::getOutputTokens).sum();
		double cost = usage.stream().mapToDouble((log) -> log.getEstimatedCostUsd().doubleValue()).sum();
		List<Long> sorted = latencies.stream().sorted().toList();
		StringJoiner report = new StringJoiner("\n");
		report.add("# 채점 평가 결과").add("");
		report.add("- 모델: `" + properties.model() + "`, effort: `" + properties.effort() + "`, 사례당 " + RUNS_PER_CASE + "회");
		report.add("- 판정 일치(과반 기준): 조정용 " + percent(tune.agreement()) + " (" + tune.total() + "건), 확인용 "
				+ percent(holdout.agreement()) + " (" + holdout.total() + "건)");
		report.add("- 3회 일관성: " + percent(all.consistency()));
		report.add("- 치명 오류(정답을 오답으로, 오답을 정답으로 판정한 사례): " + all.criticalErrors() + "건");
		report.add("- 통과 기준: 확인용 일치 " + percent(criteria.minHoldoutAgreement()) + " 이상, 일관성 "
				+ percent(criteria.minConsistency()) + " 이상, 치명 오류 " + criteria.maxCriticalErrors() + "건 이하");
		report.add("- **결과: " + (criteria.passedBy(holdout, all) ? "통과" : "미통과") + "**");
		report.add("- 호출 " + usage.size() + "회, 입력 " + input + " / 출력 " + output + " 토큰, 추산 비용 $"
				+ String.format("%.4f", cost));
		report.add("- 지연: 중앙값 " + sorted.get(sorted.size() / 2) + "ms, p95 "
				+ sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) + "ms, 최대 " + sorted.get(sorted.size() - 1) + "ms");
		report.add("").add("| 사례 | 구분 | 기준 판정 | AI 판정 | 결과 | 마지막 응답 |").add("|---|---|---|---|---|---|");
		report.add(details.toString());
		return report.toString();
	}

	private static String percent(double ratio) {
		return String.format("%.0f%%", ratio * 100);
	}

}
