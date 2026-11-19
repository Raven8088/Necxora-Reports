package com.nexora.reports.result.domain;

import com.nexora.reports.execution.domain.DatasetRows;
import com.nexora.reports.execution.domain.OutputValueType;
import com.nexora.reports.plan2.domain.LogicalPlanV2;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.result.domain.ResultAssemblyException.Code;
import com.nexora.reports.translation2.domain.SqlQueryV2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A4-B6: assembles the {@link ReportResult} of one run from validated documents and the rows of its snapshot. Pure
 * and deterministic (EX-18): the same input gives an equal result. It knows no report, table or business concept:
 * everything comes from the specification and the {@code /2} plans (PR-1, PR-2), so a new report needs no code.
 * <p>
 * Fails closed:
 * <ul>
 *   <li>inputs that do not belong together (datasets, queries and rows of another plan, values of another type than
 *       their column) are a programming error: {@link IllegalArgumentException} / {@link IllegalStateException};</li>
 *   <li>sections, items and totals that do not correspond to the datasets (D-A4-7, {@code basic-a}):
 *       {@code REPORT_LAYOUT_INVALID};</li>
 *   <li>a presentation over its cell budget, renderer additions included: {@code BUDGET_EXCEEDED}.</li>
 * </ul>
 * Totals come only from the total dataset, never from the summary (EX-09). Rows are the executor's unmodifiable rows;
 * a row is rebuilt only when one of its visible texts exceeds the cell text limit.
 */
public final class ResultAssembler {

    public static final String TENANT_NAME_UNKNOWN = "tenant_name_unknown_to_reports";
    public static final String SOURCE_NAME_NOT_PROVIDED = "source_name_not_provided";

    public ReportResult assemble(AssemblyInput input) {
        Objects.requireNonNull(input, "input");
        ValidatedReportPlan plan = input.plan();
        SpecPresentation spec = input.spec();
        if (!spec.fingerprint().equals(plan.reportPlan().specFingerprint())) {
            throw new ResultAssemblyException(Code.SPEC_MISMATCH, "the specification is not the validated one");
        }
        ZoneId zone = zone(spec.timeZone());
        checkDatasetsCorrespond(plan, spec, input);

        List<ResultWarning> warnings = new ArrayList<>();
        List<DatasetResult> datasets = new ArrayList<>();
        for (int i = 0; i < plan.datasets().size(); i++) {
            datasets.add(dataset(plan.datasets().get(i), input.queries().get(i), input.read().datasets().get(i),
                    spec.dataset(plan.datasets().get(i).datasetId()), plan, input.limits(), warnings));
        }
        List<ReportSection> sections = sections(spec, datasets, input.limits());
        List<ResultWarning> truncations = new ArrayList<>();
        for (DatasetResult d : datasets) {
            if (d.truncated()) {
                truncations.add(new ResultWarning(ResultWarning.DATASET_TRUNCATED, d.datasetId(), null,
                        d.rowCount(), d.maxRows()));
            }
        }
        warnings.addAll(0, truncations);
        List<FilterDescription> filters = filters(plan, spec);
        Methodology methodology = methodology(plan, spec, datasets, input);
        long cells = PresentationBudget.cells(sections, datasets, filters.size(), warnings.size(), methodology);
        if (cells > input.limits().maxCells()) {
            throw new ResultAssemblyException(Code.BUDGET_EXCEEDED, "report exceeds its cell budget");
        }
        ResultStatus status = datasets.stream().anyMatch(DatasetResult::truncated) ? ResultStatus.PARTIAL
                : ResultStatus.COMPLETED;
        LogicalPlanV2 first = plan.datasets().getFirst().plan();
        RunMetadata run = new RunMetadata(input.runId(), plan.reportPlan().tenantId(),
                plan.reportPlan().dataSourceId(), plan.reportPlan().fingerprint(), spec.fingerprint(),
                first.metadataSnapshotId(), "REPEATABLE_READ", true, 1, input.read().snapshot(), input.startedAt(),
                input.finishedAt(), input.read().duration());
        ReportHeader header = new ReportHeader(spec.title(), spec.requestText(), spec.requestLanguage(), zone,
                spec.labelLanguage(), plan.reportPlan().tenantId(), Fact.unavailable(TENANT_NAME_UNKNOWN),
                plan.reportPlan().dataSourceId(), input.sourceName().<Fact>map(Fact::known)
                .orElse(Fact.unavailable(SOURCE_NAME_NOT_PROVIDED)), input.generatedAt(), input.reportsVersion(),
                filters);
        return new ReportResult(run, header, status, datasets, sections, methodology, warnings, cells);
    }

    // ------------------------------------------------------------------ correspondence of the inputs

    private static void checkDatasetsCorrespond(ValidatedReportPlan plan, SpecPresentation spec, AssemblyInput input) {
        List<ValidatedReportPlan.Dataset> planned = plan.datasets();
        List<DatasetRows> read = input.read().datasets();
        if (input.queries().size() != planned.size() || read.size() != planned.size()) {
            throw new IllegalArgumentException("one query and one result per dataset are required");
        }
        for (int i = 0; i < planned.size(); i++) {
            ValidatedReportPlan.Dataset d = planned.get(i);
            SqlQueryV2 q = input.queries().get(i);
            DatasetRows r = read.get(i);
            if (!d.datasetId().equals(q.datasetId()) || !d.datasetId().equals(r.datasetId())
                    || d.maxRows() != q.maxRows() || !q.outputs().equals(r.columns())) {
                throw new IllegalArgumentException("queries or rows do not belong to the plan's datasets");
            }
            List<LogicalPlanV2.OutputColumn> outputs = d.plan().output();
            if (outputs.size() != q.outputs().size()) {
                throw new IllegalArgumentException("columns do not match the plan's output");
            }
            for (int c = 0; c < outputs.size(); c++) {
                LogicalPlanV2.OutputColumn o = outputs.get(c);
                SqlQueryV2.Output qo = q.outputs().get(c);
                if (!o.output().equals(qo.output()) || !o.valueType().equals(qo.valueType())
                        || o.hidden() != qo.hidden() || qo.position() != c + 1) {
                    throw new IllegalArgumentException("columns do not match the plan's output");
                }
            }
        }
        Set<String> planIds = new HashSet<>(planned.stream().map(ValidatedReportPlan.Dataset::datasetId).toList());
        Set<String> specIds = new HashSet<>(spec.datasets().stream().map(SpecPresentation.Dataset::id).toList());
        if (!planIds.equals(specIds)) {
            throw layout("the specification and the report plan have different datasets");
        }
        if (spec.compositions() != 0) {
            throw layout("compositions are not supported in basic-a");
        }
    }

    // ------------------------------------------------------------------ datasets

    private static DatasetResult dataset(ValidatedReportPlan.Dataset d, SqlQueryV2 query, DatasetRows rows,
                                         SpecPresentation.Dataset spec, ValidatedReportPlan plan,
                                         AssemblyLimits limits, List<ResultWarning> warnings) {
        LogicalPlanV2 p = d.plan();
        Set<String> outputs = new HashSet<>();
        List<ResultColumn2> columns = new ArrayList<>();
        for (int i = 0; i < p.output().size(); i++) {
            LogicalPlanV2.OutputColumn o = p.output().get(i);
            if (!outputs.add(o.output())) {
                throw layout("duplicate output id in a dataset");
            }
            columns.add(new ResultColumn2(i + 1, o.output(), o.label(), OutputValueType.of(o.valueType()), o.role(),
                    o.origin(), o.hidden(), sourceOf(p, o.output()), definitionOf(p, spec, o)));
        }
        List<List<Object>> source = rows.rows();
        checkTypes(source, columns);
        List<List<Object>> kept = limitTexts(d.datasetId(), source, columns, limits.maxCellText(), warnings);
        String fingerprint = plan.reportPlan().plans().stream()
                .filter(ref -> ref.datasetId().equals(d.datasetId())).findFirst().orElseThrow().planFingerprint();
        return new DatasetResult(d.datasetId(), d.planId(), fingerprint, spec.title(), spec.mode(), columns, kept,
                rows.truncated(), d.maxRows(), rows.duration());
    }

    /** The physical column behind an output: the column of its projection, group key or measure, if any. */
    private static SourceObject sourceOf(LogicalPlanV2 plan, String output) {
        LogicalPlanV2.ColumnRef ref = null;
        for (LogicalPlanV2.Operation op : plan.operations()) {
            switch (op) {
                case LogicalPlanV2.Project p -> {
                    for (LogicalPlanV2.ProjectedColumn c : p.columns()) {
                        if (c.output().equals(output)) {
                            ref = c.column();
                        }
                    }
                }
                case LogicalPlanV2.Aggregate a -> {
                    for (LogicalPlanV2.GroupKey g : a.groupBy()) {
                        if (g.output().equals(output)) {
                            ref = g.column();
                        }
                    }
                    for (LogicalPlanV2.Measure m : a.measures()) {
                        if (m.output().equals(output)) {
                            ref = m.column();
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (ref == null) {
            return null;
        }
        LogicalPlanV2.PlanObject object = plan.object(ref.column())
                .orElseThrow(() -> new IllegalStateException("plan column not found"));
        return column(object);
    }

    private static GovernedDefinition definitionOf(LogicalPlanV2 plan, SpecPresentation.Dataset spec,
                                                   LogicalPlanV2.OutputColumn output) {
        if (!"GOVERNED".equals(output.origin())) {
            return null;
        }
        UUID id = spec.select().stream().filter(s -> s.id().equals(output.output())).findFirst()
                .map(SpecPresentation.Select::definition).orElse(null);
        if (id == null) {
            return null;
        }
        return plan.definitions().stream().filter(def -> def.id().equals(id) && def.key() != null).findFirst()
                .map(def -> new GovernedDefinition(def.kind(), def.key(), def.revision())).orElse(null);
    }

    private static void checkTypes(List<List<Object>> rows, List<ResultColumn2> columns) {
        for (List<Object> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalStateException("row width differs from the columns");
            }
            for (int i = 0; i < row.size(); i++) {
                Object value = row.get(i);
                if (value != null && !isOfType(value, columns.get(i).valueType())) {
                    throw new IllegalStateException("value of another type than its column");
                }
            }
        }
    }

    private static boolean isOfType(Object value, OutputValueType type) {
        return switch (type) {
            case TEXT -> value instanceof String;
            case NUMBER -> value instanceof BigDecimal;
            case INTEGER -> value instanceof Long;
            case BOOLEAN -> value instanceof Boolean;
            case DATE -> value instanceof LocalDate;
            case TIMESTAMP -> value instanceof LocalDateTime;
            case TIMESTAMP_TZ -> value instanceof OffsetDateTime t && t.getOffset().equals(ZoneOffset.UTC);
        };
    }

    /** Cuts visible texts over the limit (copying only those rows) and records one warning per column cut. */
    private static List<List<Object>> limitTexts(String datasetId, List<List<Object>> rows,
                                                 List<ResultColumn2> columns, int max,
                                                 List<ResultWarning> warnings) {
        List<Integer> texts = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).valueType() == OutputValueType.TEXT && !columns.get(i).hidden()) {
                texts.add(i);
            }
        }
        Map<Integer, Long> cut = new LinkedHashMap<>();
        List<List<Object>> result = null;
        for (int r = 0; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            List<Object> replaced = null;
            for (int i : texts) {
                String text = (String) row.get(i);
                if (!CellText.fits(text, max)) {
                    if (replaced == null) {
                        replaced = new ArrayList<>(row);
                    }
                    replaced.set(i, CellText.limit(text, max));
                    cut.merge(i, 1L, Long::sum);
                }
            }
            if (replaced != null) {
                if (result == null) {
                    result = new ArrayList<>(rows);
                }
                result.set(r, Collections.unmodifiableList(replaced));
            }
        }
        cut.forEach((i, count) -> warnings.add(new ResultWarning(ResultWarning.CELL_TEXT_TRUNCATED, datasetId,
                columns.get(i).output(), count, max)));
        return result == null ? rows : Collections.unmodifiableList(result);
    }

    // ------------------------------------------------------------------ sections, items and totals (D-A4-7)

    private static List<ReportSection> sections(SpecPresentation spec, List<DatasetResult> datasets,
                                                 AssemblyLimits limits) {
        Set<String> used = new HashSet<>();
        List<ReportSection> sections = new ArrayList<>();
        for (SpecPresentation.Section s : spec.sections()) {
            List<ReportItem> items = new ArrayList<>();
            for (SpecPresentation.Item item : s.items()) {
                DatasetResult dataset = find(datasets, item.dataset());
                used.add(dataset.datasetId());
                switch (item.type()) {
                    case "table" -> {
                        if (!"DETAIL".equals(dataset.mode())) {
                            throw layout("a table shows a detail dataset");
                        }
                        items.add(new ReportItem.Table(dataset.datasetId()));
                    }
                    case "summaryTable" -> {
                        if (!"AGGREGATE".equals(dataset.mode())) {
                            throw layout("a summary table shows an aggregate dataset");
                        }
                        Optional<ReportItem.Total> total = Optional.empty();
                        if (item.total() != null) {
                            DatasetResult totalDataset = find(datasets, item.total());
                            used.add(totalDataset.datasetId());
                            total = Optional.of(total(dataset, totalDataset, limits));
                        }
                        items.add(new ReportItem.SummaryTable(dataset.datasetId(), total));
                    }
                    default -> throw layout("item type not supported in basic-a");
                }
            }
            sections.add(new ReportSection(s.id(), s.title(), items));
        }
        if (used.size() != datasets.size()) {
            throw layout("a dataset is not presented by any item");
        }
        return sections;
    }

    /**
     * The total row, taken only from the total dataset (precision 3; EX-09): it must be a different, aggregate,
     * ungrouped dataset with exactly one row, not truncated, whose visible columns are all measures, each paired by
     * output id with a visible measure of the summary of the same value type (D-A4-7).
     */
    private static ReportItem.Total total(DatasetResult summary, DatasetResult total, AssemblyLimits limits) {
        if (total.datasetId().equals(summary.datasetId()) || !"AGGREGATE".equals(total.mode())) {
            throw layout("the total is not an aggregate dataset of its own");
        }
        if (total.truncated() || total.rowCount() != 1) {
            throw layout("the total dataset must have exactly one row");
        }
        List<ReportItem.TotalCell> cells = new ArrayList<>();
        List<Object> row = total.rows().getFirst();
        for (ResultColumn2 column : total.visibleColumns()) {
            if (!"MEASURE".equals(column.role())) {
                throw layout("the total dataset has groups");
            }
            ResultColumn2 pair = summary.visibleColumns().stream()
                    .filter(c -> c.output().equals(column.output())).findFirst()
                    .orElseThrow(() -> layout("a total measure has no summary measure with its id"));
            if (!"MEASURE".equals(pair.role()) || pair.valueType() != column.valueType()) {
                throw layout("a total measure does not correspond to its summary measure");
            }
            Object value = row.get(column.position() - 1);
            if (value instanceof String text) {
                value = CellText.limit(text, limits.maxCellText());
            }
            cells.add(new ReportItem.TotalCell(column.output(), column.valueType(), value));
        }
        if (cells.isEmpty()) {
            throw layout("the total dataset has no measure");
        }
        return new ReportItem.Total(total.datasetId(), cells);
    }

    // ------------------------------------------------------------------ header and methodology

    private static List<FilterDescription> filters(ValidatedReportPlan plan, SpecPresentation spec) {
        List<FilterDescription> filters = new ArrayList<>();
        for (ValidatedReportPlan.Dataset d : plan.datasets()) {
            SpecPresentation.Dataset sd = spec.dataset(d.datasetId());
            for (SpecPresentation.Filter f : sd.filters()) {
                LogicalPlanV2.PlanObject object = d.plan().object(f.column())
                        .orElseThrow(() -> layout("a filter column is not in its plan"));
                String label = sd.select().stream().filter(s -> f.column().equals(s.column())).findFirst()
                        .map(SpecPresentation.Select::label).orElse(null);
                filters.add(new FilterDescription(d.datasetId(), label, column(object), f.operator(), f.values(),
                        f.literalSource()));
            }
        }
        return filters;
    }

    private static Methodology methodology(ValidatedReportPlan plan, SpecPresentation spec,
                                           List<DatasetResult> datasets, AssemblyInput input) {
        List<SourceObject> relations = new ArrayList<>();
        Map<UUID, Methodology.RelationshipUse> relationships = new LinkedHashMap<>();
        List<Methodology.DefinitionUse> definitions = new ArrayList<>();
        List<Methodology.OrderRule> orders = new ArrayList<>();
        for (ValidatedReportPlan.Dataset d : plan.datasets()) {
            LogicalPlanV2 p = d.plan();
            for (LogicalPlanV2.Operation op : p.operations()) {
                switch (op) {
                    case LogicalPlanV2.Scan s -> addRelation(relations, p, s.object());
                    case LogicalPlanV2.Join j -> {
                        LogicalPlanV2.Relationship r = p.relationship(j.relationship())
                                .orElseThrow(() -> new IllegalStateException("plan relationship not found"));
                        relationships.putIfAbsent(r.id(), new Methodology.RelationshipUse(r.id(), r.origin(),
                                r.status(), Fact.unavailable(Methodology.RELATIONSHIP_APPROVER_NOT_IN_PLAN)));
                        UUID joined = p.object(j.conditions().getFirst().right().column())
                                .map(LogicalPlanV2.PlanObject::parentId).orElse(null);
                        if (joined != null) {
                            addRelation(relations, p, joined);
                        }
                    }
                    case LogicalPlanV2.Order o -> o.keys().forEach(k -> orders.add(new Methodology.OrderRule(
                            d.datasetId(), k.output(), k.direction(), k.nulls(), k.collation())));
                    default -> {
                    }
                }
            }
        }
        for (DatasetResult d : datasets) {
            for (ResultColumn2 c : d.columns()) {
                if (c.definition() != null) {
                    definitions.add(new Methodology.DefinitionUse(d.datasetId(), c.output(), c.definition()));
                }
            }
        }
        List<Methodology.DatasetExecution> executions = datasets.stream().map(d -> new Methodology.DatasetExecution(
                d.datasetId(), d.planId(), d.planFingerprint(), d.rowCount(), d.truncated(), d.maxRows(),
                d.duration().toMillis())).toList();
        Map<UUID, String> conceptKeys = new LinkedHashMap<>();
        for (ValidatedReportPlan.Dataset d : plan.datasets()) {
            d.plan().definitions().forEach(def -> conceptKeys.putIfAbsent(def.id(), def.key()));
        }
        List<Methodology.Term> terms = spec.terms().stream()
                .map(t -> new Methodology.Term(t.text(), t.resolution(), t.target(), t.intent(),
                        t.entity() == null ? null : conceptKeys.getOrDefault(t.entity(), t.entity().toString()),
                        t.evidence())).toList();
        UUID snapshot = plan.datasets().getFirst().plan().metadataSnapshotId();
        return new Methodology(relations, List.copyOf(relationships.values()), terms, definitions, orders, executions,
                snapshot == null ? Fact.unavailable("metadata_snapshot_not_in_plan") : Fact.known(snapshot.toString()),
                input.excludedColumns().<Fact>map(Fact::known)
                        .orElse(Fact.unavailable(Methodology.EXCLUDED_COLUMNS_NOT_PROVIDED)),
                Fact.unavailable(Methodology.MODEL_NOT_RECORDED), Fact.unavailable(Methodology.UNITS_NOT_IN_CONTRACTS));
    }

    private static void addRelation(List<SourceObject> relations, LogicalPlanV2 plan, UUID id) {
        LogicalPlanV2.PlanObject object = plan.object(id)
                .orElseThrow(() -> new IllegalStateException("plan object not found"));
        SourceObject relation = new SourceObject(object.type().name(), object.naturalKey().schema(),
                object.naturalKey().name(), null);
        if (!relations.contains(relation)) {
            relations.add(relation);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static SourceObject column(LogicalPlanV2.PlanObject object) {
        LogicalPlanV2.NaturalKey key = object.naturalKey();
        return new SourceObject(object.type().name(), key.schema(), key.name(), key.column());
    }

    private static DatasetResult find(List<DatasetResult> datasets, String id) {
        return datasets.stream().filter(d -> d.datasetId().equals(id)).findFirst()
                .orElseThrow(() -> layout("an item refers to a dataset that does not exist"));
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            throw layout("unknown time zone");
        }
    }

    private static ResultAssemblyException layout(String reason) {
        return new ResultAssemblyException(Code.REPORT_LAYOUT_INVALID, reason);
    }
}
