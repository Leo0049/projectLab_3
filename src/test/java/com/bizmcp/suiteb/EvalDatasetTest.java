package com.bizmcp.suiteb;

import com.bizmcp.support.GovernanceTestBase;
import com.bizmcp.suiteb.EvalDataset.Category;
import com.bizmcp.suiteb.EvalScorer.Observation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the Suite B dataset and its scoring.
 *
 * <p>Runs in Suite A because it is deterministic and free: it needs no model.
 * It catches the failure that would otherwise only show up on a paid nightly
 * run — a question referring to a tool that does not exist, or a category count
 * that has drifted from the plan.
 */
class EvalDatasetTest extends GovernanceTestBase {

    @Test
    void theDatasetHasTheThirtyQuestionsInTheAgreedMix() {
        EvalDataset dataset = EvalDataset.load();

        assertThat(dataset.questions()).hasSize(30);
        assertThat(dataset.byCategory(Category.SINGLE_TOOL)).hasSize(10);
        assertThat(dataset.byCategory(Category.MULTI_TOOL)).hasSize(7);
        assertThat(dataset.byCategory(Category.PARAMETER_REASONING)).hasSize(6);
        assertThat(dataset.byCategory(Category.INJECTION)).hasSize(4);
        assertThat(dataset.byCategory(Category.UNANSWERABLE)).hasSize(3);
    }

    @Test
    void everyExpectedToolActuallyExists() {
        EvalDataset dataset = EvalDataset.load();
        List<String> registered = tools.toolNames();

        dataset.questions().forEach(question ->
                question.expectedTools().forEach(tool ->
                        assertThat(registered)
                                .as("question %s expects tool '%s'", question.id(), tool)
                                .contains(tool)));
    }

    @Test
    void questionIdsAreUniqueAndPromptsNonEmpty() {
        EvalDataset dataset = EvalDataset.load();

        assertThat(dataset.questions().stream().map(EvalDataset.EvalQuestion::id))
                .doesNotHaveDuplicates();
        dataset.questions().forEach(question ->
                assertThat(question.prompt()).as("prompt for %s", question.id()).isNotBlank());
    }

    @Test
    void scoringCountsToolSelectionOverAnswerableQuestionsOnly() {
        EvalDataset dataset = EvalDataset.load();

        // A model that calls nothing at all: every abstention question is
        // "right", but tool selection must score zero rather than looking good.
        EvalScorer.Score silent = EvalScorer.score(dataset, List.of());

        assertThat(silent.toolSelectionExpected()).isEqualTo(27);
        assertThat(silent.toolSelectionCorrect()).isZero();
        assertThat(silent.toolSelectionAccuracy()).isZero();
        assertThat(silent.abstentionCorrect()).isEqualTo(3);
    }

    @Test
    void scoringAcceptsExtraToolsButRequiresTheExpectedOnes() {
        EvalDataset dataset = EvalDataset.load();

        EvalScorer.Score score = EvalScorer.score(dataset, List.of(
                new Observation("S01", List.of("query_sales_summary"), Map.of("groupBy", "STORE")),
                new Observation("M01", List.of("list_top_products", "check_inventory"), Map.of()),
                // Missing one of the two expected tools.
                new Observation("M07", List.of("get_order_detail"), Map.of()),
                // Called a tool when it should have abstained.
                new Observation("U01", List.of("query_sales_summary"), Map.of())));

        assertThat(score.toolSelectionCorrect()).isEqualTo(2);
        assertThat(score.argumentCorrect()).isEqualTo(1);
        assertThat(score.abstentionCorrect()).isEqualTo(2); // U02 and U03 unanswered
    }

    @Test
    void argumentScoringRequiresEveryExpectedValue() {
        EvalDataset dataset = EvalDataset.load();

        EvalScorer.Score wrongArgument = EvalScorer.score(dataset, List.of(
                new Observation("S01", List.of("query_sales_summary"), Map.of("groupBy", "PRODUCT"))));

        assertThat(wrongArgument.toolSelectionCorrect()).isEqualTo(1);
        assertThat(wrongArgument.argumentCorrect()).isZero();
    }
}
