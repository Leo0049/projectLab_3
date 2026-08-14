package com.bizmcp.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code query-templates.yml}.
 *
 * <p>Read as a standalone document rather than through the Spring Environment:
 * the template set is domain configuration, not application settings, and
 * keeping it separate means a test can point at a different file (including a
 * deliberately malformed one) to prove the startup checks actually fail.
 */
@Component
public class QueryTemplateProperties {

    private final List<QueryTemplate> templates;

    public QueryTemplateProperties(@Value("${bizmcp.query.templates-location:classpath:query-templates.yml}")
                                   Resource location) {
        this.templates = load(location);
    }

    private static List<QueryTemplate> load(Resource location) {
        YAMLMapper mapper = YAMLMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        try (InputStream in = location.getInputStream()) {
            Document document = mapper.readValue(in, Document.class);
            return document.templates() == null ? new ArrayList<>() : document.templates();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read query templates from " + location, e);
        }
    }

    public List<QueryTemplate> getTemplates() {
        return templates;
    }

    private record Document(List<QueryTemplate> templates) {
    }
}
