package com.nexora.reports.plan2.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.nexora.reports.plan2.domain.Trees.list;
import static com.nexora.reports.plan2.domain.Trees.map;
import static com.nexora.reports.plan2.domain.Trees.maps;
import static com.nexora.reports.plan2.domain.Trees.string;

/**
 * What Reports executes in profile {@code basic-a} (A4 proposal v3, section 2), on top of the contracts. A document
 * can be valid for its contract and still be refused here: Reports does not assume that Semantic produced a correct
 * plan (defense in depth) and executes only the subset Semantic produces for {@code basic-a}.
 * <p>
 * Every method expects a document that already satisfies its schema and its contract rules ({@link ContractRules}).
 * Results are paths of violations of rule {@code BASIC-A}; no value from the document is copied into them.
 */
final class BasicAProfile {

    static final String PROFILE = ReportsProfile.BASIC_A;
    private static final Set<String> RELATIONS = Set.of("TABLE", "VIEW");
    private static final Set<String> UNSUPPORTED_CATEGORIES = Set.of("TIME", "OTHER");
    private static final Set<String> RANGE_OPERATORS = Set.of("GT", "GTE", "LT", "LTE");
    private static final Set<String> NON_RANGE_CATEGORIES = Set.of("TEXT", "UUID", "BOOLEAN");
    /** Canonical decimal text, as Semantic writes it: no exponent, no leading zeros, no trailing fractional zeros. */
    private static final Pattern CANONICAL_NUMBER = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?$");

    private BasicAProfile() {
    }

    static List<String> reportPlan(Map<String, Object> reportPlan) {
        return PROFILE.equals(reportPlan.get("profile")) ? List.of() : List.of("report-plan.profile");
    }

    static List<String> spec(Map<String, Object> spec) {
        List<String> violations = new ArrayList<>();
        if (!PROFILE.equals(spec.get("profile"))) {
            violations.add("spec.profile");
        }
        if (!"READY".equals(spec.get("status"))) {
            violations.add("spec.status");
        }
        // Sections, datasets, joins per dataset, item types, aggregations and compositions of basic-a are limited
        // by the schema of nexora-report-spec/1 itself (checked before this profile).
        return violations;
    }

    /** Violations of a logical plan; {@code at} prefixes the paths (for example {@code logical-plan[resumen]}). */
    static List<String> logicalPlan(Map<String, Object> plan, String at) {
        return new PlanCheck(plan, at).run();
    }

    private static final class PlanCheck {

        private final Map<String, Object> plan;
        private final String at;
        private final List<String> violations = new ArrayList<>();
        private final Map<String, Map<String, Object>> objects = new HashMap<>();
        private final Map<String, Map<String, Object>> relationships = new HashMap<>();
        private final Map<String, String> objectOfAlias = new HashMap<>();

        PlanCheck(Map<String, Object> plan, String at) {
            this.plan = plan;
            this.at = at;
            maps(plan, "objects").forEach(o -> objects.put(string(o, "id"), o));
            maps(plan, "relationships").forEach(r -> relationships.put(string(r, "id"), r));
        }

        List<String> run() {
            if (!list(plan, "parameters").isEmpty()) {
                fail("parameters");
            }
            checkObjects();
            List<Map<String, Object>> ops = maps(plan, "operations");
            // A4-B10 (D-B10-4): at most MAX_JOINS_PER_DATASET joins, as announced in nexora-capabilities/1; the
            // schema of nexora-logical-plan/2 does not limit them (the one of the specification does).
            if (ops.stream().filter(op -> "JOIN".equals(op.get("op"))).count() > ReportsProfile.MAX_JOINS_PER_DATASET) {
                fail("joins");
            }
            for (int i = 0; i < ops.size(); i++) {
                Map<String, Object> op = ops.get(i);
                String here = "operations[" + i + "]";
                String name = string(op, "op");
                if (!ReportsProfile.PLAN_OPERATIONS.contains(name)) {
                    fail(here + ".op"); // GROUP_FILTER and anything outside basic-a
                    continue;
                }
                switch (name) {
                    case "SCAN" -> scan(op, here);
                    case "JOIN" -> join(op, here);
                    case "FILTER" -> filter(op, here);
                    case "AGGREGATE" -> aggregate(op, here);
                    case "PROJECT" -> project(op, here);
                    case "ORDER" -> order(op, here);
                    case "LIMIT" -> {
                        // the limit is checked against the budgets by PlanV2Validator
                    }
                    default -> fail(here + ".op"); // unreachable: every operation of PLAN_OPERATIONS has a case
                }
            }
            return violations;
        }

        private void checkObjects() {
            List<Map<String, Object>> list = maps(plan, "objects");
            for (int i = 0; i < list.size(); i++) {
                Map<String, Object> o = list.get(i);
                if (!"COLUMN".equals(o.get("type"))) {
                    continue;
                }
                Map<String, Object> parent = o.get("parentId") == null ? null : objects.get(string(o, "parentId"));
                if (parent == null || !RELATIONS.contains(string(parent, "type"))
                        || o.get("typeCategory") == null
                        || UNSUPPORTED_CATEGORIES.contains(string(o, "typeCategory"))) {
                    fail("objects[" + i + "]");
                }
            }
        }

        private void scan(Map<String, Object> op, String here) {
            Map<String, Object> object = objects.get(string(op, "object"));
            if (object == null || !RELATIONS.contains(string(object, "type"))) {
                fail(here + ".object");
                return;
            }
            objectOfAlias.put(string(op, "alias"), string(op, "object"));
        }

        private void join(Map<String, Object> op, String here) {
            Map<String, Object> relationship = relationships.get(string(op, "relationship"));
            if (relationship == null || !"APPROVED".equals(relationship.get("status"))
                    || !ReportsProfile.UNIQUENESS.contains(relationship.get("targetUniqueness"))
                    || !ReportsProfile.UNIQUENESS.contains(op.get("uniqueness"))
                    || !list(relationship, "allowedJoinTypes").contains(op.get("type"))) {
                fail(here);
                return;
            }
            String from = string(op, "from");
            String alias = string(op, "alias");
            boolean forward = "FORWARD".equals(op.get("direction"));
            Set<List<String>> expected = new HashSet<>();
            for (Map<String, Object> pair : maps(relationship, "pairs")) {
                String source = string(pair, "source");
                String target = string(pair, "target");
                expected.add(forward ? List.of(source, target) : List.of(target, source));
            }
            Set<List<String>> actual = new HashSet<>();
            Set<String> joinedColumns = new HashSet<>();
            String joinedObject = null;
            List<Map<String, Object>> conditions = maps(op, "conditions");
            for (int i = 0; i < conditions.size(); i++) {
                Map<String, Object> condition = conditions.get(i);
                String cond = here + ".conditions[" + i + "]";
                if (!condition.containsKey("right")) {
                    fail(cond); // equality with a parameter: not in basic-a
                    return;
                }
                Map<String, Object> left = map(condition, "left");
                Map<String, Object> right = map(condition, "right");
                if (!from.equals(left.get("alias")) || !alias.equals(right.get("alias"))
                        || !ownedBy(string(left, "column"), objectOfAlias.get(from))) {
                    fail(cond);
                    return;
                }
                String parent = parentOf(string(right, "column"));
                if (parent == null || (joinedObject != null && !joinedObject.equals(parent))) {
                    fail(cond);
                    return;
                }
                joinedObject = parent;
                joinedColumns.add(string(right, "column"));
                actual.add(List.of(string(left, "column"), string(right, "column")));
            }
            Map<String, Object> joined = objects.get(joinedObject);
            if (!actual.equals(expected) || conditions.size() != expected.size() || joined == null
                    || !RELATIONS.contains(string(joined, "type")) || objectOfAlias.containsValue(joinedObject)
                    || !isPrimaryKey(joined, joinedColumns)) {
                fail(here);
                return;
            }
            objectOfAlias.put(alias, joinedObject);
        }

        private void filter(Map<String, Object> op, String here) {
            List<Map<String, Object>> predicates = maps(op, "predicates");
            for (int i = 0; i < predicates.size(); i++) {
                Map<String, Object> predicate = predicates.get(i);
                String p = here + ".predicates[" + i + "]";
                String category = column(map(predicate, "column"), p + ".column");
                if (category == null) {
                    continue;
                }
                String operator = string(predicate, "operator");
                if (!ReportsProfile.FILTER_OPERATORS.contains(operator)) {
                    fail(p + ".operator");
                    continue;
                }
                List<Map<String, Object>> values = maps(predicate, "values");
                boolean countOk = switch (operator) {
                    case "IS_NULL", "IS_NOT_NULL" -> values.isEmpty();
                    case "IN" -> !values.isEmpty() && values.size() <= 100;
                    default -> values.size() == 1;
                };
                if (!countOk || (RANGE_OPERATORS.contains(operator) && NON_RANGE_CATEGORIES.contains(category))) {
                    fail(p);
                    continue;
                }
                for (int j = 0; j < values.size(); j++) {
                    Map<String, Object> value = values.get(j);
                    if (!literalType(category).equals(value.get("type"))
                            || !isCanonical(string(value, "value"), category)) {
                        fail(p + ".values[" + j + "]");
                    }
                }
            }
        }

        private void aggregate(Map<String, Object> op, String here) {
            List<Map<String, Object>> groupBy = maps(op, "groupBy");
            for (int i = 0; i < groupBy.size(); i++) {
                Map<String, Object> group = groupBy.get(i);
                String category = column(map(group, "column"), here + ".groupBy[" + i + "].column");
                expectOutput(string(group, "output"), valueTypesOf(category), Set.of("DIMENSION"));
            }
            List<Map<String, Object>> measures = maps(op, "measures");
            for (int i = 0; i < measures.size(); i++) {
                Map<String, Object> measure = measures.get(i);
                String m = here + ".measures[" + i + "]";
                if (!ReportsProfile.AGGREGATIONS.contains(measure.get("aggregation"))) {
                    fail(m + ".aggregation");
                    continue;
                }
                if (measure.containsKey("column")) {
                    column(map(measure, "column"), m + ".column");
                }
                expectOutput(string(measure, "output"), Set.of("INTEGER"), Set.of("MEASURE"));
            }
        }

        private void project(Map<String, Object> op, String here) {
            List<Map<String, Object>> columns = maps(op, "columns");
            for (int i = 0; i < columns.size(); i++) {
                Map<String, Object> projected = columns.get(i);
                String category = column(map(projected, "column"), here + ".columns[" + i + "].column");
                expectOutput(string(projected, "output"), valueTypesOf(category), Set.of("DETAIL", "KEY"));
            }
        }

        private void order(Map<String, Object> op, String here) {
            List<Map<String, Object>> keys = maps(op, "keys");
            for (int i = 0; i < keys.size(); i++) {
                Map<String, Object> key = keys.get(i);
                Map<String, Object> output = output(string(key, "output"));
                boolean text = output != null && "TEXT".equals(output.get("valueType"));
                // Text keys are always ordered by bytes (COLLATE "C"); no collation applies to other keys.
                if (text != ReportsProfile.COLLATION.equals(key.get("collation"))
                        || (!text && key.containsKey("collation"))) {
                    fail(here + ".keys[" + i + "]");
                }
            }
        }

        /** Category of a referenced column, or null (and a violation) if it is not a column of the alias. */
        private String column(Map<String, Object> ref, String path) {
            String alias = string(ref, "alias");
            String columnId = string(ref, "column");
            if (!ownedBy(columnId, objectOfAlias.get(alias))) {
                fail(path);
                return null;
            }
            return string(objects.get(columnId), "typeCategory");
        }

        private boolean ownedBy(String columnId, String relationId) {
            Map<String, Object> column = objects.get(columnId);
            return relationId != null && column != null && "COLUMN".equals(column.get("type"))
                    && relationId.equals(column.get("parentId"));
        }

        private String parentOf(String columnId) {
            Map<String, Object> column = objects.get(columnId);
            return column == null || !"COLUMN".equals(column.get("type")) ? null : string(column, "parentId");
        }

        private static boolean isPrimaryKey(Map<String, Object> relation, Set<String> columns) {
            if (!(relation.get("uniqueKeys") instanceof List<?> keys)) {
                return false;
            }
            for (Object key : keys) {
                Map<String, Object> uniqueKey = map(key);
                if (ReportsProfile.PRIMARY_KEY.equals(uniqueKey.get("kind"))
                        && new HashSet<>(list(uniqueKey, "columns")).equals(new HashSet<Object>(columns))) {
                    return true;
                }
            }
            return false;
        }

        private void expectOutput(String name, Set<String> valueTypes, Set<String> roles) {
            Map<String, Object> output = output(name);
            if (output == null || !valueTypes.contains(string(output, "valueType"))
                    || !roles.contains(string(output, "role"))) {
                fail("output.columns[" + name + "]");
            }
        }

        private Map<String, Object> output(String name) {
            for (Map<String, Object> column : maps(map(plan, "output"), "columns")) {
                if (name.equals(column.get("output"))) {
                    return column;
                }
            }
            return null;
        }

        private void fail(String path) {
            violations.add(at + "." + path);
        }
    }

    /** Value types an output may have for a column category (decision X-7 of A3). */
    static Set<String> valueTypesOf(String category) {
        if (category == null) {
            return Set.of();
        }
        return switch (category) {
            case "NUMERIC" -> Set.of("INTEGER", "NUMBER");
            case "TEXT", "UUID" -> Set.of("TEXT");
            case "BOOLEAN" -> Set.of("BOOLEAN");
            case "DATE" -> Set.of("DATE");
            case "TIMESTAMP" -> Set.of("TIMESTAMP");
            case "TIMESTAMP_TZ" -> Set.of("TIMESTAMP_TZ");
            default -> Set.of();
        };
    }

    /** Literal type of a filter value for a column category (one type per category, as Semantic compiles it). */
    static String literalType(String category) {
        return switch (category) {
            case "NUMERIC" -> "NUMBER";
            case "TEXT", "UUID" -> "TEXT";
            case "BOOLEAN" -> "BOOLEAN";
            case "DATE" -> "DATE";
            case "TIMESTAMP", "TIMESTAMP_TZ" -> "TIMESTAMP";
            default -> "";
        };
    }

    /** True if the literal is already in the canonical form Semantic writes for that column category. */
    static boolean isCanonical(String value, String category) {
        try {
            return switch (category) {
                case "NUMERIC" -> CANONICAL_NUMBER.matcher(value).matches()
                        && canonicalNumber(new BigDecimal(value)).equals(value);
                case "TEXT" -> true;
                case "UUID" -> UUID.fromString(value).toString().equals(value);
                case "BOOLEAN" -> "true".equals(value) || "false".equals(value);
                case "DATE" -> LocalDate.parse(value).toString().equals(value);
                case "TIMESTAMP" -> LocalDateTime.parse(value).toString().equals(value);
                case "TIMESTAMP_TZ" -> OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toString()
                        .equals(value);
                default -> false;
            };
        } catch (DateTimeParseException | IllegalArgumentException | ArithmeticException e) {
            return false;
        }
    }

    private static String canonicalNumber(BigDecimal number) {
        return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
    }
}
