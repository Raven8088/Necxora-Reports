package com.nexora.reports.plan.infrastructure;

import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.plan.domain.DataSourceDescriptor;
import com.nexora.reports.plan.domain.LogicalPlan;
import com.nexora.reports.plan.domain.PlanNotExecutableException;
import com.nexora.reports.plan.domain.PlanValidity;
import com.nexora.reports.plan.domain.PlanViolation.Code;
import com.nexora.reports.plan.domain.StoredPlan;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

import java.util.UUID;

/**
 * Strict reader of the JSON documents returned by Nexora Semantic.
 * <ul>
 *   <li>Unknown properties, unknown enum values, duplicate keys, numbers given as text (or text as numbers or
 *       booleans) and trailing content make the document unreadable: a contract Reports does not know is never
 *       interpreted.</li>
 *   <li>The envelope's {@code formatVersion} is checked before the plan body is bound, so a future format is
 *       reported as unsupported rather than as malformed.</li>
 *   <li>Error paths name fields and indexes only; values are never copied into errors or logs.</li>
 * </ul>
 */
public class PlanDocumentReader {

    private final JsonMapper strict = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(LogicalType.Integer, c -> c.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Boolean, c -> c.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Textual, c -> c.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Enum, c -> c.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
            .build();

    /** Same envelope as Semantic's {@code PlanResponse}. */
    private record PlanEnvelope(UUID id, UUID dataSourceId, String formatVersion, String fingerprint,
                                String createdBy, java.time.Instant createdAt, LogicalPlan plan) {
    }

    public StoredPlan plan(byte[] body) {
        JsonNode tree;
        try {
            tree = strict.readTree(body);
        } catch (JacksonException e) {
            throw PlanNotExecutableException.of(Code.UNREADABLE_DOCUMENT, "$");
        }
        if (tree == null || !tree.isObject()) {
            throw PlanNotExecutableException.of(Code.UNREADABLE_DOCUMENT, "$");
        }
        JsonNode version = tree.get("formatVersion");
        JsonNode inner = tree.path("plan").get("formatVersion");
        if (version == null || !version.isString() || !LogicalPlan.FORMAT_VERSION.equals(version.asString())
                || inner == null || !inner.isString() || !LogicalPlan.FORMAT_VERSION.equals(inner.asString())) {
            throw PlanNotExecutableException.of(Code.UNSUPPORTED_FORMAT_VERSION, "formatVersion");
        }
        try {
            PlanEnvelope envelope = strict.treeToValue(tree, PlanEnvelope.class);
            return new StoredPlan(envelope.id(), envelope.dataSourceId(), envelope.formatVersion(),
                    envelope.fingerprint(), envelope.createdBy(), envelope.createdAt(), envelope.plan());
        } catch (JacksonException e) {
            throw PlanNotExecutableException.of(Code.UNREADABLE_DOCUMENT, path(e));
        }
    }

    /** Validity responses must follow the known shape exactly; otherwise validity is unconfirmed. */
    public PlanValidity validity(byte[] body) {
        try {
            PlanValidity validity = strict.readValue(body, PlanValidity.class);
            if (validity == null) {
                throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "empty validity document");
            }
            return validity;
        } catch (JacksonException e) {
            throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED,
                    "unreadable validity document at " + path(e));
        }
    }

    /** Only id, engine and status are read; the data source document has other fields Reports does not need. */
    public DataSourceDescriptor dataSource(byte[] body) {
        try {
            JsonNode tree = strict.readTree(body);
            JsonNode id = tree == null ? null : tree.get("id");
            JsonNode engine = tree == null ? null : tree.get("engine");
            JsonNode status = tree == null ? null : tree.get("status");
            if (id == null || !id.isString() || engine == null || !engine.isString() || status == null
                    || !status.isString()) {
                throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "data source document");
            }
            return new DataSourceDescriptor(UUID.fromString(id.asString()), engine.asString(), status.asString());
        } catch (JacksonException | IllegalArgumentException e) {
            throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "unreadable data source document");
        }
    }

    /** Error code of a Semantic problem document ({@code code} property), or null. */
    public String problemCode(byte[] body) {
        try {
            JsonNode tree = strict.readTree(body);
            JsonNode code = tree == null ? null : tree.get("code");
            return code != null && code.isString() ? code.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** JSON path built from property names and indexes only (never from values). */
    static String path(JacksonException e) {
        StringBuilder path = new StringBuilder("$");
        for (JacksonException.Reference reference : e.getPath()) {
            if (reference.getPropertyName() != null) {
                path.append('.').append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
