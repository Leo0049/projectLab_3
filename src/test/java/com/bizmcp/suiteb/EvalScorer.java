package com.bizmcp.suiteb;

import com.bizmcp.suiteb.EvalDataset.EvalQuestion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Scores an evaluation run (spec section 11.3).
 *
 * <p>Kept separate from whatever drives the model so the arithmetic is unit
 * testable. The metric definitions matter as much as the numbers: "tool
 * selection accuracy" is measured over questions that <em>should</em> produce a
 * call, because counting the unanswerable ones as successes would inflate the
 * score for doing nothing.
 */
public final class EvalScorer {

    /** What the model actually did for one question. */
    public record Observation(String questionId,
                              List<String> calledTools,
                              Map<String, Object> arguments) {
    }

    public record Score(
            int totalQuestions,
            int toolSelectionExpected,
            int toolSelectionCorrect,
            int argumentChecked,
            int argumentCorrect,
            int abstentionExpected,
            int abstentionCorrect
    ) {
        public double toolSelectionAccuracy() {
            return ratio(toolSelectionCorrect, toolSelectionExpected);
        }

        public double argumentAccuracy() {
            return ratio(argumentCorrect, argumentChecked);
        }

        public double abstentionAccuracy() {
            return ratio(abstentionCorrect, abstentionExpected);
        }

        private static double ratio(int numerator, int denominator) {
            return denominator == 0 ? Double.NaN : (double) numerator / denominator * 100.0;
        }
    }

    private EvalScorer() {
    }

    public static Score score(EvalDataset dataset, List<Observation> observations) {
        Map<String, Observation> byId = new LinkedHashMap<>();
        observations.forEach(observation -> byId.put(observation.questionId(), observation));

        int toolExpected = 0;
        int toolCorrect = 0;
        int argChecked = 0;
        int argCorrect = 0;
        int abstentionExpected = 0;
        int abstentionCorrect = 0;

        for (EvalQuestion question : dataset.questions()) {
            Observation observation = byId.get(question.id());
            List<String> called = observation == null ? List.of() : observation.calledTools();

            if (question.isUnanswerable()) {
                abstentionExpected++;
                // Correct means calling nothing: inventing a tool call here is
                // the failure mode worth measuring.
                if (called.isEmpty()) {
                    abstentionCorrect++;
                }
                continue;
            }

            toolExpected++;
            if (called.containsAll(question.expectedTools())) {
                toolCorrect++;
            }

            Map<String, Object> expectedArguments = question.expectedArguments();
            if (expectedArguments != null && !expectedArguments.isEmpty()) {
                argChecked++;
                if (observation != null && matches(expectedArguments, observation.arguments())) {
                    argCorrect++;
                }
            }
        }

        return new Score(dataset.questions().size(), toolExpected, toolCorrect,
                argChecked, argCorrect, abstentionExpected, abstentionCorrect);
    }

    /** Every expected argument must be present and equal; extras are allowed. */
    private static boolean matches(Map<String, Object> expected, Map<String, Object> actual) {
        if (actual == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object actualValue = actual.get(entry.getKey());
            if (!Objects.equals(String.valueOf(entry.getValue()), String.valueOf(actualValue))) {
                return false;
            }
        }
        return true;
    }
}
