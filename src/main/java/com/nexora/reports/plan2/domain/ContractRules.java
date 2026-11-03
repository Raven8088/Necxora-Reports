package com.nexora.reports.plan2.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static com.nexora.reports.plan2.domain.Trees.list;
import static com.nexora.reports.plan2.domain.Trees.map;
import static com.nexora.reports.plan2.domain.Trees.maps;
import static com.nexora.reports.plan2.domain.Trees.string;

/**
 * Referential rules of the contracts, as written in their READMEs and in the reference validator
 * {@code nexora-contracts/tools/validar_contratos.py} ({@code reglas_spec}, {@code reglas_plan},
 * {@code reglas_report_plan}). This class is a faithful port of that validator: same conditions, same rule names,
 * sorted result. It runs only on documents that already satisfy their schema.
 * <p>
 * Profile restrictions of Reports ({@code basic-a}) are not here but in {@link BasicAProfile}: this class answers
 * "is the document valid for its contract", exactly as the contract tools do.
 */
public final class ContractRules {

    private static final Map<String, Integer> RANK = Map.of("SCAN", 0, "JOIN", 1, "FILTER", 3, "AGGREGATE", 4,
            "PROJECT", 4, "GROUP_FILTER", 5, "ORDER", 6, "LIMIT", 7);

    private ContractRules() {
    }

    /** SPEC-R1…SPEC-R6 of {@code nexora-report-spec/1}; they apply only to documents with status READY. */
    public static List<String> reportSpec(Map<String, Object> d) {
        Set<String> e = new TreeSet<>();
        if (!"READY".equals(d.get("status"))) {
            return List.of();
        }
        List<Map<String, Object>> datasets = maps(d, "datasets");
        List<String> datasetIds = datasets.stream().map(x -> string(x, "id")).toList();
        if (new HashSet<>(datasetIds).size() != datasetIds.size()) {
            e.add("SPEC-R1");
        }
        List<String> sectionIds = maps(d, "sections").stream().map(x -> string(x, "id")).toList();
        if (new HashSet<>(sectionIds).size() != sectionIds.size()) {
            e.add("SPEC-R1");
        }
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> ds : datasets) {
            byId.put(string(ds, "id"), ds);
        }
        for (Map<String, Object> ds : datasets) {
            List<Map<String, Object>> select = maps(ds, "select");
            List<String> sel = select.stream().map(s -> string(s, "id")).toList();
            if (new HashSet<>(sel).size() != sel.size()) {
                e.add("SPEC-R2");
            }
            List<String> aliases = new ArrayList<>();
            aliases.add(string(map(ds, "base"), "alias"));
            for (Map<String, Object> join : maps(ds, "joins")) {
                if (aliases.contains(string(join, "alias")) || !aliases.contains(string(join, "from"))) {
                    e.add("SPEC-R3");
                }
                aliases.add(string(join, "alias"));
            }
            for (Map<String, Object> s : select) {
                Object column = s.get("column");
                if (column == null && s.get("measure") instanceof Map<?, ?> measure) {
                    column = measure.get("column");
                }
                if (column instanceof Map<?, ?> c && !c.isEmpty() && !aliases.contains(c.get("alias"))) {
                    e.add("SPEC-R3");
                }
            }
            for (Map<String, Object> f : maps(ds, "filters")) {
                if (f.containsKey("column") && !aliases.contains(string(map(f, "column"), "alias"))) {
                    e.add("SPEC-R3");
                }
            }
            List<Object> groupBy = list(ds, "groupBy");
            boolean groupOutside = groupBy.stream().anyMatch(g -> !sel.contains(g));
            boolean orderOutside = maps(ds, "orderBy").stream().anyMatch(k -> !sel.contains(string(k, "select")));
            if (groupOutside || orderOutside) {
                e.add("SPEC-R4");
            }
            List<String> measures = select.stream().filter(s -> s.containsKey("measure"))
                    .map(s -> string(s, "id")).toList();
            List<String> nonMeasures = select.stream().filter(s -> !s.containsKey("measure"))
                    .map(s -> string(s, "id")).toList();
            if ("AGGREGATE".equals(ds.get("mode"))) {
                if (measures.isEmpty() || !new HashSet<>(groupBy).equals(new HashSet<Object>(nonMeasures))
                        || groupBy.size() != nonMeasures.size()) {
                    e.add("SPEC-R5");
                }
            } else if (!measures.isEmpty() || !groupBy.isEmpty()) {
                e.add("SPEC-R5");
            }
        }
        for (Map<String, Object> section : maps(d, "sections")) {
            for (Map<String, Object> item : maps(section, "items")) {
                if (!item.containsKey("dataset")) {
                    continue;
                }
                Map<String, Object> ds = byId.get(string(item, "dataset"));
                if (ds == null) {
                    e.add("SPEC-R6");
                    continue;
                }
                String type = string(item, "type");
                boolean aggregate = "AGGREGATE".equals(ds.get("mode"));
                boolean grouped = !list(ds, "groupBy").isEmpty();
                if ("table".equals(type) && !"DETAIL".equals(ds.get("mode"))) {
                    e.add("SPEC-R6");
                }
                if ("summaryTable".equals(type)) {
                    if (!aggregate || !grouped) {
                        e.add("SPEC-R6");
                    }
                    if (item.containsKey("total")) {
                        Map<String, Object> total = byId.get(string(item, "total"));
                        if (total == null || !"AGGREGATE".equals(total.get("mode"))
                                || !list(total, "groupBy").isEmpty()) {
                            e.add("SPEC-R6");
                        }
                    }
                }
                if ("kpi".equals(type) && (!aggregate || grouped)) {
                    e.add("SPEC-R6");
                }
            }
        }
        return List.copyOf(e);
    }

    /** PLAN-R1…PLAN-R5 of {@code nexora-logical-plan/2}. */
    public static List<String> logicalPlan(Map<String, Object> d) {
        Set<String> e = new TreeSet<>();
        List<Map<String, Object>> ops = maps(d, "operations");
        List<String> names = ops.stream().map(o -> string(o, "op")).toList();
        List<Integer> ranks = names.stream().map(RANK::get).toList();
        List<Integer> sortedRanks = ranks.stream().sorted().toList();
        if (!"SCAN".equals(names.getFirst()) || !"LIMIT".equals(names.getLast()) || count(names, "SCAN") != 1
                || count(names, "ORDER") != 1 || count(names, "LIMIT") != 1 || count(names, "FILTER") > 1
                || count(names, "GROUP_FILTER") > 1 || count(names, "AGGREGATE") + count(names, "PROJECT") != 1
                || !ranks.equals(sortedRanks)
                || (names.contains("GROUP_FILTER") && !names.contains("AGGREGATE"))) {
            e.add("PLAN-R1");
        }
        Map<String, Map<String, Object>> objects = new LinkedHashMap<>();
        for (Map<String, Object> o : maps(d, "objects")) {
            objects.put(string(o, "id"), o);
        }
        Set<String> aliases = new HashSet<>();
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> o : ops) {
            switch (string(o, "op")) {
                case "SCAN" -> {
                    aliases.add(string(o, "alias"));
                    if (!objects.containsKey(string(o, "object"))) {
                        e.add("PLAN-R5");
                    }
                }
                case "JOIN" -> {
                    if (aliases.contains(string(o, "alias")) || !aliases.contains(string(o, "from"))) {
                        e.add("PLAN-R2");
                    }
                    aliases.add(string(o, "alias"));
                    for (Map<String, Object> c : maps(o, "conditions")) {
                        columns.add(map(c, "left"));
                        if (c.containsKey("right")) {
                            columns.add(map(c, "right"));
                        }
                    }
                }
                case "FILTER" -> maps(o, "predicates").forEach(p -> columns.add(map(p, "column")));
                case "AGGREGATE" -> {
                    maps(o, "groupBy").forEach(g -> columns.add(map(g, "column")));
                    maps(o, "measures").stream().filter(m -> m.containsKey("column"))
                            .forEach(m -> columns.add(map(m, "column")));
                }
                case "PROJECT" -> maps(o, "columns").forEach(c -> columns.add(map(c, "column")));
                default -> {
                    // GROUP_FILTER, ORDER and LIMIT reference outputs, not columns
                }
            }
        }
        for (Map<String, Object> c : columns) {
            if (!aliases.contains(string(c, "alias"))) {
                e.add("PLAN-R2");
            }
            if (!objects.containsKey(string(c, "column"))) {
                e.add("PLAN-R5");
            }
        }
        List<String> output = maps(map(d, "output"), "columns").stream().map(c -> string(c, "output")).toList();
        Map<String, Object> aggregate = first(ops, "AGGREGATE");
        Map<String, Object> project = first(ops, "PROJECT");
        Map<String, Object> order = first(ops, "ORDER");
        List<String> produced = new ArrayList<>();
        if (aggregate != null) {
            maps(aggregate, "groupBy").forEach(g -> produced.add(string(g, "output")));
            maps(aggregate, "measures").forEach(m -> produced.add(string(m, "output")));
        } else if (project != null) {
            maps(project, "columns").forEach(c -> produced.add(string(c, "output")));
        }
        if (!produced.equals(output)) {
            e.add("PLAN-R4");
        }
        if (order != null) {
            List<String> keys = maps(order, "keys").stream().map(k -> string(k, "output")).toList();
            if (keys.stream().anyMatch(k -> !output.contains(k))) {
                e.add("PLAN-R4");
            }
            Map<String, Object> tieBreaker = map(order, "tieBreaker");
            String kind = string(tieBreaker, "kind");
            List<Object> tieOutputs = list(tieBreaker, "outputs");
            if (aggregate != null && !maps(aggregate, "groupBy").isEmpty()) {
                Set<String> groupOutputs = new HashSet<>();
                maps(aggregate, "groupBy").forEach(g -> groupOutputs.add(string(g, "output")));
                if (!"GROUP_KEYS".equals(kind) || !keys.containsAll(groupOutputs)) {
                    e.add("PLAN-R3");
                }
            } else if (aggregate != null) {
                if (!"SINGLE_ROW".equals(kind)) {
                    e.add("PLAN-R3");
                }
            } else if (project != null) {
                String baseId = ops.stream().filter(o -> "SCAN".equals(o.get("op"))).map(o -> string(o, "object"))
                        .findFirst().orElse(null);
                Map<String, Object> base = baseId == null ? null : objects.get(baseId);
                Map<String, String> columnByOutput = new HashMap<>();
                maps(project, "columns").forEach(c -> columnByOutput.put(string(c, "output"),
                        string(map(c, "column"), "column")));
                Set<String> tieColumns = new HashSet<>();
                tieOutputs.forEach(x -> tieColumns.add(columnByOutput.get(x)));
                List<Set<Object>> uniqueKeys = new ArrayList<>();
                if (base != null && base.get("uniqueKeys") instanceof List<?> keysOfBase) {
                    for (Object key : keysOfBase) {
                        uniqueKeys.add(new HashSet<>(list(map(key), "columns")));
                    }
                }
                boolean tieInKeys = keys.containsAll(tieOutputs.stream().map(Object::toString).toList());
                if (!"UNIQUE_KEY".equals(kind) || !tieInKeys
                        || uniqueKeys.stream().noneMatch(k -> k.equals(new HashSet<Object>(tieColumns)))) {
                    e.add("PLAN-R3");
                }
            }
        }
        return List.copyOf(e);
    }

    /** RPLAN-R1 of {@code nexora-report-plan/1}: unique datasets, no more than {@code budget.maxPlans}. */
    public static List<String> reportPlan(Map<String, Object> d) {
        List<String> ids = maps(d, "plans").stream().map(p -> string(p, "datasetId")).toList();
        long maxPlans = Trees.number(map(d, "budget"), "maxPlans");
        return new HashSet<>(ids).size() != ids.size() || ids.size() > maxPlans ? List.of("RPLAN-R1") : List.of();
    }

    private static long count(List<String> names, String name) {
        return names.stream().filter(name::equals).count();
    }

    private static Map<String, Object> first(List<Map<String, Object>> ops, String name) {
        return ops.stream().filter(o -> Objects.equals(name, o.get("op"))).findFirst().orElse(null);
    }
}
