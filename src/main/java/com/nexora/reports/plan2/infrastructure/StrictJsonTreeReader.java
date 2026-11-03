package com.nexora.reports.plan2.infrastructure;

import com.nexora.reports.plan2.domain.PlanV2Exception;
import com.nexora.reports.plan2.domain.PlanV2Violation;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict reader of the contract documents served by Nexora Semantic ({@code nexora-report-plan/1},
 * {@code nexora-logical-plan/2}, {@code nexora-report-spec/1}). It produces a plain tree (maps, lists, strings,
 * booleans, {@code Long}/{@code BigInteger}, null) that the domain validates and fingerprints.
 * <ul>
 *   <li>Duplicate keys and trailing content make a document unreadable: two readers must never see different values
 *       behind the same fingerprint.</li>
 *   <li>Fractional or exponent numbers are rejected: the contracts only hold integers, and their canonical form
 *       (fingerprints) is only defined for integers.</li>
 *   <li>The root must be an object.</li>
 * </ul>
 * Errors name the document only; values are never copied into errors or logs.
 */
public class StrictJsonTreeReader {

    private final JsonMapper strict = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * @param document name of the document for error paths (for example {@code report-plan}), never its content
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readObject(byte[] json, String document) {
        JsonNode tree;
        try {
            tree = strict.readTree(json);
        } catch (JacksonException e) {
            throw unreadable(document);
        }
        if (tree == null || !tree.isObject()) {
            throw unreadable(document);
        }
        return (Map<String, Object>) convert(tree, document);
    }

    private static Object convert(JsonNode node, String document) {
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                map.put(entry.getKey(), convert(entry.getValue(), document));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (int i = 0; i < node.size(); i++) {
                list.add(convert(node.get(i), document));
            }
            return list;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? (Object) node.longValue() : node.bigIntegerValue();
        }
        throw unreadable(document); // fractional numbers and any other node type
    }

    private static PlanV2Exception unreadable(String document) {
        return PlanV2Exception.of("UNREADABLE", PlanV2Violation.Kind.CONTRACT_INVALID, document);
    }
}
