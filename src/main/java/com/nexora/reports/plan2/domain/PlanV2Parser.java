package com.nexora.reports.plan2.domain;

import com.nexora.reports.plan2.domain.LogicalPlanV2.Aggregate;
import com.nexora.reports.plan2.domain.LogicalPlanV2.ColumnRef;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Filter;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Join;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Limit;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Operation;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Order;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Project;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Scan;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexora.reports.plan2.domain.Trees.integer;
import static com.nexora.reports.plan2.domain.Trees.list;
import static com.nexora.reports.plan2.domain.Trees.map;
import static com.nexora.reports.plan2.domain.Trees.maps;
import static com.nexora.reports.plan2.domain.Trees.number;
import static com.nexora.reports.plan2.domain.Trees.string;
import static com.nexora.reports.plan2.domain.Trees.uuid;

/**
 * Builds the immutable models from trees that {@link PlanV2Validator} has already validated completely. It performs
 * no validation of its own: an unexpected shape here is a programming error and fails with an exception.
 */
final class PlanV2Parser {

    private PlanV2Parser() {
    }

    static ReportPlanV1 reportPlan(Map<String, Object> d) {
        Map<String, Object> b = map(d, "budget");
        return new ReportPlanV1(uuid(d, "tenantId"), uuid(d, "dataSourceId"), string(d, "profile"),
                string(d, "specFingerprint"),
                maps(d, "plans").stream().map(p -> new ReportPlanV1.PlanRef(string(p, "datasetId"),
                        uuid(p, "planId"), string(p, "planFingerprint"))).toList(),
                new ReportPlanV1.Budget(integer(b, "maxPlans"), integer(b, "maxRowsPerPlan"),
                        integer(b, "maxTotalRows"), number(b, "statementTimeoutMs"), number(b, "maxDurationMs")),
                string(d, CanonicalJson.FINGERPRINT_FIELD));
    }

    static ReportSpecView spec(Map<String, Object> d, String fingerprint) {
        return new ReportSpecView(string(d, "profile"), string(d, "status"),
                maps(d, "datasets").stream().map(x -> string(x, "id")).toList(), fingerprint);
    }

    static LogicalPlanV2 logicalPlan(Map<String, Object> d) {
        return new LogicalPlanV2(uuid(d, "tenantId"), uuid(d, "dataSourceId"), uuid(d, "metadataSnapshotId"),
                string(d, "specFingerprint"), string(d, "datasetId"),
                maps(d, "objects").stream().map(PlanV2Parser::object).toList(),
                maps(d, "definitions").stream().map(x -> new LogicalPlanV2.Definition(uuid(x, "id"),
                        string(x, "kind"), string(x, "origin"), string(x, "key"),
                        x.containsKey("revision") ? integer(x, "revision") : null)).toList(),
                maps(d, "relationships").stream().map(PlanV2Parser::relationship).toList(),
                maps(d, "operations").stream().map(PlanV2Parser::operation).toList(),
                maps(map(d, "output"), "columns").stream().map(c -> new LogicalPlanV2.OutputColumn(
                        string(c, "output"), string(c, "label"), string(c, "valueType"), string(c, "role"),
                        Boolean.TRUE.equals(c.get("hidden")), string(c, "origin"))).toList(),
                string(d, CanonicalJson.FINGERPRINT_FIELD));
    }

    private static LogicalPlanV2.PlanObject object(Map<String, Object> o) {
        Map<String, Object> nk = map(o, "naturalKey");
        List<LogicalPlanV2.UniqueKey> keys = o.containsKey("uniqueKeys")
                ? maps(o, "uniqueKeys").stream().map(k -> new LogicalPlanV2.UniqueKey(string(k, "kind"),
                list(k, "columns").stream().map(c -> UUID.fromString((String) c)).toList())).toList()
                : List.of();
        return new LogicalPlanV2.PlanObject(uuid(o, "id"), LogicalPlanV2.ObjectType.valueOf(string(o, "type")),
                new LogicalPlanV2.NaturalKey(string(nk, "schema"), string(nk, "name"), string(nk, "column")),
                uuid(o, "parentId"),
                o.containsKey("typeCategory") ? LogicalPlanV2.Category.valueOf(string(o, "typeCategory")) : null,
                (Boolean) o.get("nullable"), keys);
    }

    private static LogicalPlanV2.Relationship relationship(Map<String, Object> r) {
        return new LogicalPlanV2.Relationship(uuid(r, "id"), string(r, "origin"), string(r, "status"),
                maps(r, "pairs").stream().map(p -> new LogicalPlanV2.Pair(uuid(p, "source"), uuid(p, "target")))
                        .toList(),
                string(r, "targetUniqueness"),
                list(r, "allowedJoinTypes").stream().map(String.class::cast).toList());
    }

    private static Operation operation(Map<String, Object> o) {
        return switch (string(o, "op")) {
            case "SCAN" -> new Scan(string(o, "alias"), uuid(o, "object"));
            case "JOIN" -> new Join(string(o, "alias"), string(o, "from"), uuid(o, "relationship"),
                    string(o, "direction"), string(o, "type"), string(o, "uniqueness"),
                    maps(o, "conditions").stream().map(c -> new LogicalPlanV2.JoinCondition(
                            column(map(c, "left")), column(map(c, "right")))).toList());
            case "FILTER" -> new Filter(maps(o, "predicates").stream().map(p -> new LogicalPlanV2.Predicate(
                    column(map(p, "column")), string(p, "operator"),
                    maps(p, "values").stream().map(v -> new LogicalPlanV2.Literal(string(v, "type"),
                            string(v, "value"))).toList())).toList());
            case "AGGREGATE" -> new Aggregate(
                    maps(o, "groupBy").stream().map(g -> new LogicalPlanV2.GroupKey(string(g, "output"),
                            column(map(g, "column")))).toList(),
                    maps(o, "measures").stream().map(m -> new LogicalPlanV2.Measure(string(m, "output"),
                            string(m, "aggregation"), m.containsKey("column") ? column(map(m, "column")) : null))
                            .toList());
            case "PROJECT" -> new Project(maps(o, "columns").stream().map(c -> new LogicalPlanV2.ProjectedColumn(
                    string(c, "output"), column(map(c, "column")))).toList());
            case "ORDER" -> {
                Map<String, Object> tie = map(o, "tieBreaker");
                yield new Order(maps(o, "keys").stream().map(k -> new LogicalPlanV2.OrderKey(string(k, "output"),
                        string(k, "direction"), string(k, "nulls"), string(k, "collation"))).toList(),
                        string(tie, "kind"), list(tie, "outputs").stream().map(String.class::cast).toList());
            }
            case "LIMIT" -> new Limit(integer(o, "maxRows"));
            default -> throw new IllegalStateException("operation not validated for basic-a");
        };
    }

    private static ColumnRef column(Map<String, Object> c) {
        return new ColumnRef(string(c, "alias"), uuid(c, "column"));
    }
}
