package com.nexora.reports.render.layout;

import java.util.Locale;

/**
 * Every fixed text of a report in Spanish, English and French (the {@code labelLanguage} values of
 * {@code nexora-report-spec/1}). An enum, so that no text can be missing in a language. {@code {0}}, {@code {1}}...
 * are replaced by {@link #format}. The sheet names «Reporte» and «Metodología» are the literal names of
 * {@code nexora-excel-report/1} (EX-01) in every language (D-B7-2).
 */
public enum Label {
    SHEET_REPORT("Reporte", "Reporte", "Reporte"),
    SHEET_METHODOLOGY("Metodología", "Metodología", "Metodología"),
    TITLE("Título", "Title", "Titre"),
    REQUEST("Solicitud", "Request", "Demande"),
    SOURCE("Fuente", "Source", "Source"),
    TENANT("Tenant", "Tenant", "Locataire"),
    GENERATED("Generado", "Generated", "Généré"),
    FILTERS("Filtros", "Filters", "Filtres"),
    NO_FILTERS("Sin filtros", "No filters", "Aucun filtre"),
    PERIOD("Periodo", "Period", "Période"),
    NOT_APPLICABLE("No aplica", "Not applicable", "Sans objet"),
    VERSION("Versión de Nexora", "Nexora version", "Version de Nexora"),
    STATUS("Estado", "Status", "État"),
    STATUS_COMPLETED("Completo", "Complete", "Complet"),
    STATUS_PARTIAL("Parcial", "Partial", "Partiel"),
    WARNING("Aviso", "Warning", "Avertissement"),
    WARNING_TRUNCATED("«{0}» entregó {1} filas, el límite; la fuente tiene más filas.",
            "«{0}» delivered {1} rows, the limit; the source has more rows.",
            "«{0}» a livré {1} lignes, la limite ; la source contient davantage de lignes."),
    WARNING_TEXT_CUT("«{0}», columna «{1}»: {2} textos recortados a {3} caracteres (marca {4}).",
            "«{0}», column «{1}»: {2} texts cut to {3} characters (mark {4}).",
            "«{0}», colonne «{1}» : {2} textes coupés à {3} caractères (marque {4})."),
    WARNING_AS_TEXT("Para conservar su precisión, {0} valores se escribieron como texto: {1}.",
            "To keep their precision, {0} values were written as text: {1}.",
            "Pour conserver leur précision, {0} valeurs ont été écrites en texte : {1}."),
    NO_VALUE("(sin valor)", "(no value)", "(sans valeur)"),
    YES("Sí", "Yes", "Oui"),
    NO("No", "No", "Non"),
    TOTAL("Total general", "Grand total", "Total général"),
    SEE_SHEET("Ver la hoja «{0}» ({1} filas).", "See sheet «{0}» ({1} rows).", "Voir la feuille «{0}» ({1} lignes)."),
    UNAVAILABLE("No disponible: {0}", "Not available: {0}", "Non disponible : {0}"),
    OP_EQ("es igual a", "equals", "est égal à"),
    OP_NE("es distinto de", "is not", "est différent de"),
    OP_GT("es mayor que", "is greater than", "est supérieur à"),
    OP_GTE("es mayor o igual que", "is at least", "est supérieur ou égal à"),
    OP_LT("es menor que", "is less than", "est inférieur à"),
    OP_LTE("es menor o igual que", "is at most", "est inférieur ou égal à"),
    OP_IN("es uno de", "is one of", "est l'un de"),
    OP_IS_NULL("está vacío", "is empty", "est vide"),
    OP_IS_NOT_NULL("no está vacío", "is not empty", "n'est pas vide"),
    REASON_TENANT_NAME("Reports no conoce el nombre del tenant", "Reports does not know the tenant's name",
            "Reports ne connaît pas le nom du locataire"),
    REASON_SOURCE_NAME("el nombre de la fuente no se proporcionó", "the source's name was not provided",
            "le nom de la source n'a pas été fourni"),
    REASON_APPROVER("el plan no indica quién aprobó la relación", "the plan does not state who approved the relationship",
            "le plan n'indique pas qui a approuvé la relation"),
    REASON_EXCLUDED("las columnas excluidas no se proporcionaron", "the excluded columns were not provided",
            "les colonnes exclues n'ont pas été fournies"),
    REASON_MODEL("la especificación no registra el modelo", "the specification does not record the model",
            "la spécification n'enregistre pas le modèle"),
    REASON_UNITS("los contratos no transportan unidades", "the contracts carry no units",
            "les contrats ne transportent pas d'unités"),
    REASON_SNAPSHOT("el plan no indica la instantánea de metadatos", "the plan does not state the metadata snapshot",
            "le plan n'indique pas l'instantané des métadonnées"),
    REASON_OTHER("motivo {0}", "reason {0}", "motif {0}"),
    M_EXECUTION("Ejecución", "Execution", "Exécution"),
    M_SPEC_FINGERPRINT("Huella de la especificación", "Specification fingerprint", "Empreinte de la spécification"),
    M_PLAN_FINGERPRINT("Huella del plan de reporte", "Report plan fingerprint", "Empreinte du plan de rapport"),
    M_METADATA_SNAPSHOT("Instantánea de metadatos", "Metadata snapshot", "Instantané des métadonnées"),
    M_ISOLATION("Aislamiento", "Isolation", "Isolation"),
    M_ISOLATION_VALUE("{0}, solo lectura, {1} transacción", "{0}, read only, {1} transaction",
            "{0}, lecture seule, {1} transaction"),
    M_SNAPSHOT("Instantánea de la lectura (pg_current_snapshot)", "Read snapshot (pg_current_snapshot)",
            "Instantané de lecture (pg_current_snapshot)"),
    M_STARTED("Inicio", "Started", "Début"),
    M_FINISHED("Fin", "Finished", "Fin"),
    M_READ_DURATION("Duración de la lectura (ms)", "Read duration (ms)", "Durée de lecture (ms)"),
    M_DATASET("Conjunto de datos", "Dataset", "Jeu de données"),
    M_PLAN("Huella del plan", "Plan fingerprint", "Empreinte du plan"),
    M_ROWS("Filas", "Rows", "Lignes"),
    M_TRUNCATED("Truncado", "Truncated", "Tronqué"),
    M_LIMIT("Límite", "Limit", "Limite"),
    M_DURATION("Duración (ms)", "Duration (ms)", "Durée (ms)"),
    M_ORIGIN("Origen", "Origin", "Origine"),
    M_OBJECT("Objeto", "Object", "Objet"),
    M_TYPE("Tipo", "Type", "Type"),
    M_RELATIONSHIP("Relación", "Relationship", "Relation"),
    M_STATE("Estado", "State", "État"),
    M_APPROVED_BY("Aprobada por", "Approved by", "Approuvée par"),
    M_TERM("Término", "Term", "Terme"),
    M_RESOLUTION("Resolución", "Resolution", "Résolution"),
    M_TARGET("Destino", "Target", "Cible"),
    M_COLUMN("Columna", "Column", "Colonne"),
    M_DEFINITION("Definición", "Definition", "Définition"),
    M_REVISION("Revisión", "Revision", "Révision"),
    M_RULES("Reglas", "Rules", "Règles"),
    M_NULLS("Nulos", "Nulls", "Valeurs nulles"),
    M_NULLS_RULE("Una clave de grupo nula aparece como «(sin valor)»; un valor nulo de detalle o de medida queda vacío, nunca 0.",
            "A null group key appears as «(no value)»; a null detail or measure value is left empty, never 0.",
            "Une clé de groupe nulle apparaît comme «(sans valeur)» ; une valeur nulle de détail ou de mesure reste vide, jamais 0."),
    M_ORDER("Orden", "Order", "Ordre"),
    M_COLLATION("Intercalación", "Collation", "Collation"),
    M_EXCLUDED("Columnas excluidas", "Excluded columns", "Colonnes exclues"),
    M_MODEL("Modelo", "Model", "Modèle"),
    M_UNITS("Unidades", "Units", "Unités");

    private final String es;
    private final String en;
    private final String fr;

    Label(String es, String en, String fr) {
        this.es = es;
        this.en = en;
        this.fr = fr;
    }

    public String in(String language) {
        return switch (language == null ? "es" : language.toLowerCase(Locale.ROOT)) {
            case "en" -> en;
            case "fr" -> fr;
            default -> es;
        };
    }

    /** The text in {@code language} with {@code {0}}, {@code {1}}... replaced by {@code arguments}. */
    public String format(String language, Object... arguments) {
        String text = in(language);
        for (int i = 0; i < arguments.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(arguments[i]));
        }
        return text;
    }

    /** The reason of an unavailable datum (the stable codes of A4-B6), in {@code language}. */
    public static String reason(String code, String language) {
        return switch (code) {
            case "tenant_name_unknown_to_reports" -> REASON_TENANT_NAME.in(language);
            case "source_name_not_provided" -> REASON_SOURCE_NAME.in(language);
            case "relationship_approver_not_in_plan" -> REASON_APPROVER.in(language);
            case "excluded_columns_not_provided" -> REASON_EXCLUDED.in(language);
            case "model_not_recorded_in_specification" -> REASON_MODEL.in(language);
            case "units_not_in_contracts" -> REASON_UNITS.in(language);
            case "metadata_snapshot_not_in_plan" -> REASON_SNAPSHOT.in(language);
            default -> REASON_OTHER.format(language, code);
        };
    }

    public static Label operator(String operator) {
        return switch (operator) {
            case "EQ" -> OP_EQ;
            case "NE" -> OP_NE;
            case "GT" -> OP_GT;
            case "GTE" -> OP_GTE;
            case "LT" -> OP_LT;
            case "LTE" -> OP_LTE;
            case "IN" -> OP_IN;
            case "IS_NULL" -> OP_IS_NULL;
            case "IS_NOT_NULL" -> OP_IS_NOT_NULL;
            default -> throw new IllegalArgumentException("unknown operator");
        };
    }
}
