package com.bizmcp.suiteb;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** The Suite B question set (spec section 11.3). */
public record EvalDataset(String evaluatedOn, List<EvalQuestion> questions) {

    public enum Category {
        SINGLE_TOOL,
        MULTI_TOOL,
        PARAMETER_REASONING,
        INJECTION,
        UNANSWERABLE
    }

    public record EvalQuestion(
            String id,
            Category category,
            String prompt,
            List<String> expectedTools,
            Map<String, Object> expectedArguments,
            String notes
    ) {
        /** True when a correct answer involves no tool call at all. */
        public boolean isUnanswerable() {
            return expectedTools == null || expectedTools.isEmpty();
        }
    }

    public static EvalDataset load() {
        Resource resource = new FileSystemResource("docs/eval/questions.yml");
        if (!resource.exists()) {
            resource = new ClassPathResource("eval/questions.yml");
        }
        return load(resource);
    }

    public static EvalDataset load(Resource resource) {
        YAMLMapper mapper = YAMLMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        try (InputStream in = resource.getInputStream()) {
            return mapper.readValue(in, EvalDataset.class);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read eval questions from " + resource, e);
        }
    }

    public List<EvalQuestion> byCategory(Category category) {
        return questions.stream().filter(question -> question.category() == category).toList();
    }
}
