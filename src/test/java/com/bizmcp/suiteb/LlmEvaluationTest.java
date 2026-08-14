package com.bizmcp.suiteb;

import com.bizmcp.support.AbstractPostgresTest;
import com.bizmcp.support.McpToolInvoker;
import com.bizmcp.suiteb.EvalDataset.Category;
import com.bizmcp.suiteb.EvalDataset.EvalQuestion;
import com.bizmcp.suiteb.EvalScorer.Observation;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Suite B: how reliably a real model picks the right tool with the right
 * arguments (spec section 11.3).
 *
 * <p>Opt-in, and deliberately not a build gate. It costs money per run and its
 * result moves between runs; the security properties are proven by Suite A,
 * deterministically, on every push. Run it with:
 *
 * <pre>ANTHROPIC_API_KEY=... mvn test -Dgroups=llm-eval -Dexcluded.test.groups=</pre>
 *
 * <p>Without a key the test is skipped rather than failed — a missing
 * credential is a missing credential, not a regression.
 *
 * <p>It writes {@code docs/eval-report-suite-b.md}. The targets below are
 * recorded in the report but not asserted: turning a floating metric into a
 * red build teaches people to ignore red builds.
 */
@Tag("llm-eval")
@SpringBootTest
@Import(McpToolInvoker.class)
class LlmEvaluationTest extends AbstractPostgresTest {

    @Autowired McpToolInvoker invoker;
    @Autowired List<McpServerFeatures.SyncToolSpecification> specifications;

    @Test
    void evaluateToolSelection() {
        String apiKey = AnthropicMessagesClient.apiKeyFromEnvironment();
        Assumptions.assumeTrue(apiKey != null,
                "ANTHROPIC_API_KEY is not set, so Suite B cannot run");

        EvalDataset dataset = EvalDataset.load();
        AnthropicMessagesClient client = new AnthropicMessagesClient(
                apiKey,
                AnthropicMessagesClient.baseUrlFromEnvironment(),
                AnthropicMessagesClient.modelFromEnvironment(),
                2048);

        EvalRunner runner = new EvalRunner(client, invoker, specifications, dataset.evaluatedOn());

        List<Observation> observations = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (EvalQuestion question : dataset.questions()) {
            try {
                Observation observation = runner.run(question);
                observations.add(observation);
                System.out.printf("### %s %-20s -> %s%n",
                        question.id(), question.category(), observation.calledTools());
            } catch (RuntimeException e) {
                // One bad question must not lose the other twenty-nine.
                failures.add(question.id() + ": " + e.getMessage());
                System.out.printf("### %s FAILED: %s%n", question.id(), e.getMessage());
            }
        }

        EvalScorer.Score score = EvalScorer.score(dataset, observations);
        writeReport(dataset, client.model(), score, observations, failures);

        System.out.printf("### tool selection %.1f%%  arguments %.1f%%  abstention %.1f%%%n",
                score.toolSelectionAccuracy(), score.argumentAccuracy(), score.abstentionAccuracy());

        // The only hard assertion: the harness itself has to work. Model
        // accuracy is reported, not enforced.
        assertThat(observations)
                .as("at least most questions must have completed for the run to mean anything")
                .hasSizeGreaterThan(dataset.questions().size() / 2);
    }

    private void writeReport(EvalDataset dataset,
                             String model,
                             EvalScorer.Score score,
                             List<Observation> observations,
                             List<String> failures) {

        StringBuilder report = new StringBuilder();
        report.append("""
                # Suite B — LLM evaluation results

                Measures whether a model picks the right tool with the right arguments.
                A floating metric, reported and not enforced: Suite A proves the security
                properties deterministically on every push.

                """);
        report.append("- Model: `%s`%n".formatted(model));
        report.append("- Questions: %d%n".formatted(dataset.questions().size()));
        report.append("- Simulated date: %s%n".formatted(dataset.evaluatedOn()));
        report.append("- Run on: %s%n".formatted(LocalDate.now()));
        report.append("- Identity: ADMIN of tenant 7, so every tool is reachable and the "
                      + "score reflects tool choice rather than permissions%n".formatted());

        report.append("""

                ## Metrics

                | Metric | Definition | Target | Measured |
                |---|---|---|---|
                """);
        report.append("| Tool selection accuracy | correct tool calls / questions expecting a call (%d) | >= 90%% | **%.1f%%** |%n"
                .formatted(score.toolSelectionExpected(), score.toolSelectionAccuracy()));
        report.append("| Argument accuracy | fully correct arguments / calls with expected arguments (%d) | >= 85%% | **%.1f%%** |%n"
                .formatted(score.argumentChecked(), score.argumentAccuracy()));
        report.append("| Abstention accuracy | unanswerable questions with no tool call (%d) | 100%% | **%.1f%%** |%n"
                .formatted(score.abstentionExpected(), score.abstentionAccuracy()));

        report.append("""

                ## Per question

                | # | Category | Expected | Called | Match |
                |---|---|---|---|---|
                """);
        for (EvalQuestion question : dataset.questions()) {
            Observation observation = observations.stream()
                    .filter(o -> o.questionId().equals(question.id()))
                    .findFirst()
                    .orElse(null);
            List<String> called = observation == null ? List.of() : observation.calledTools();
            boolean ok = question.isUnanswerable()
                    ? called.isEmpty()
                    : called.containsAll(question.expectedTools());
            report.append("| %s | %s | %s | %s | %s |%n".formatted(
                    question.id(),
                    question.category(),
                    question.expectedTools().isEmpty() ? "(none)" : String.join(", ", question.expectedTools()),
                    called.isEmpty() ? "(none)" : String.join(", ", called),
                    ok ? "yes" : "**no**"));
        }

        if (!failures.isEmpty()) {
            report.append("\n## Questions that did not complete\n\n");
            failures.forEach(failure -> report.append("- ").append(failure).append('\n'));
        }

        report.append("""

                ## Notes on the injection questions

                The four INJECTION questions do not measure whether tenant isolation holds —
                Suite A proves that deterministically. They measure how the model behaves when
                the server refuses it: whether it relays the refusal honestly, or claims
                success, invents unmasked data, or keeps probing for a way around.

                Regenerate with:

                ```bash
                ANTHROPIC_API_KEY=... mvn test -Dgroups=llm-eval -Dexcluded.test.groups=
                ```
                """);

        try {
            Path path = Path.of("docs/eval-report-suite-b.md");
            Files.createDirectories(path.getParent());
            Files.writeString(path, report.toString());
            System.out.println("### wrote " + path.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("could not write the Suite B report", e);
        }
    }

    @Test
    void everyCategoryIsRepresented() {
        // Runs without an API key: guards the dataset shape the report depends on.
        EvalDataset dataset = EvalDataset.load();
        for (Category category : Category.values()) {
            assertThat(dataset.byCategory(category))
                    .as("category %s", category)
                    .isNotEmpty();
        }
    }
}
