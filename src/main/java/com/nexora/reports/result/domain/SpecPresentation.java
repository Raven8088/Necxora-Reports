package com.nexora.reports.result.domain;

import com.nexora.reports.plan2.domain.CanonicalJson;
import com.nexora.reports.plan2.domain.Sha256;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The presentation view of a {@code nexora-report-spec/1} (A4-B6, D-B6-1): title, request, parameters, datasets
 * (titles, select items, filters), sections and provenance. B2 validates the whole document but keeps only what
 * execution needs, so this view is rebuilt here from the same tree, and only if the SHA-256 of its canonical JSON is
 * exactly the {@code specFingerprint} the report plan was validated with: a report can never be presented with a
 * specification other than the validated one.
 */
public record SpecPresentation(String fingerprint, String title, String requestText, String requestLanguage,
                               String timeZone, String labelLanguage, List<Dataset> datasets, List<Section> sections,
                               int compositions, List<Term> terms) {

    public SpecPresentation {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(title, "title");
        datasets = List.copyOf(datasets);
        sections = List.copyOf(sections);
        terms = List.copyOf(terms);
    }

    public record Dataset(String id, String title, String mode, List<Select> select, List<Filter> filters) {
        public Dataset {
            select = List.copyOf(select);
            filters = List.copyOf(filters);
        }
    }

    /** @param column the object of a column item, or of a measure's column; {@code definition} if governed */
    public record Select(String id, String label, String origin, UUID column, UUID definition) {
    }

    public record Filter(UUID column, String operator, List<Literal> values, String literalSource) {
        public Filter {
            values = List.copyOf(values);
        }
    }

    public record Literal(String type, String value) {
    }

    public record Section(String id, String title, List<Item> items) {
        public Section {
            items = List.copyOf(items);
        }
    }

    /** @param total the total dataset of a {@code summaryTable}, or {@code null} */
    public record Item(String type, String dataset, String total) {
    }

    /**
     * PR3-INT: {@code intent}, {@code entity}, {@code ref} and {@code evidence} are null when the specification does
     * not declare them (every specification before PR3-INT).
     */
    public record Term(String text, String resolution, String target, String intent, UUID entity, UUID ref,
                       String evidence) {
        public Term(String text, String resolution, String target) {
            this(text, resolution, target, null, null, null, null);
        }
    }

    public Dataset dataset(String id) {
        return datasets.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResultAssemblyException(ResultAssemblyException.Code.REPORT_LAYOUT_INVALID,
                        "dataset not in the specification"));
    }

    /**
     * Reads the presentation of the specification tree that was validated with {@code plan}. Fails with
     * {@code SPEC_MISMATCH} unless its canonical SHA-256 equals both the report plan's {@code specFingerprint} and
     * the fingerprint B2 recorded for the specification.
     */
    public static SpecPresentation from(Map<String, Object> tree, ValidatedReportPlan plan, Sha256 sha256) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(plan, "plan");
        String fingerprint = CanonicalJson.sha256(tree, sha256);
        if (!fingerprint.equals(plan.reportPlan().specFingerprint()) || !fingerprint.equals(plan.spec().fingerprint())) {
            throw new ResultAssemblyException(ResultAssemblyException.Code.SPEC_MISMATCH,
                    "the specification is not the validated one");
        }
        Map<String, Object> request = map(tree, "request");
        Map<String, Object> parameters = map(tree, "parameters");
        List<Dataset> datasets = new ArrayList<>();
        for (Map<String, Object> d : maps(tree, "datasets")) {
            List<Select> select = new ArrayList<>();
            for (Map<String, Object> s : maps(d, "select")) {
                UUID column = uuidOf(s.get("column"));
                UUID definition = uuid(s.get("definition"));
                if (s.get("measure") instanceof Map<?, ?> measure) {
                    column = column != null ? column : uuidOf(measure.get("column"));
                    definition = definition != null ? definition : uuid(measure.get("definition"));
                }
                select.add(new Select(text(s, "id"), text(s, "label"), text(s, "origin"), column, definition));
            }
            List<Filter> filters = new ArrayList<>();
            for (Map<String, Object> f : maps(d, "filters")) {
                List<Literal> values = new ArrayList<>();
                for (Map<String, Object> v : maps(f, "values")) {
                    values.add(new Literal(text(v, "type"), text(v, "value")));
                }
                filters.add(new Filter(uuidOf(f.get("column")), text(f, "operator"), values,
                        text(f, "literalSource")));
            }
            datasets.add(new Dataset(text(d, "id"), (String) d.get("title"), text(d, "mode"), select, filters));
        }
        List<Section> sections = new ArrayList<>();
        for (Map<String, Object> s : maps(tree, "sections")) {
            List<Item> items = new ArrayList<>();
            for (Map<String, Object> i : maps(s, "items")) {
                items.add(new Item(text(i, "type"), (String) i.get("dataset"), (String) i.get("total")));
            }
            sections.add(new Section(text(s, "id"), text(s, "title"), items));
        }
        List<Term> terms = new ArrayList<>();
        for (Map<String, Object> t : maps(map(tree, "provenance"), "terms")) {
            terms.add(new Term(text(t, "text"), text(t, "resolution"), text(t, "target"), (String) t.get("intent"),
                    uuid(t.get("entity")), uuid(t.get("ref")), (String) t.get("evidence")));
        }
        return new SpecPresentation(fingerprint, text(tree, "title"), text(request, "text"),
                text(request, "language"), text(parameters, "timeZone"), text(parameters, "labelLanguage"),
                datasets, sections, maps(tree, "compositions").size(), terms);
    }

    // ------------------------------------------------------------------ helpers (the tree is already validated)

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> m, String key) {
        Object value = m.get(key);
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    private static String text(Map<String, Object> m, String key) {
        return (String) m.get(key);
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString((String) value);
    }

    private static UUID uuidOf(Object columnRef) {
        return columnRef instanceof Map<?, ?> ref && ref.get("object") != null
                ? UUID.fromString((String) ref.get("object")) : null;
    }
}
