package com.nexora.reports.architecture;

import com.nexora.reports.execution.application.port.TenantScopeVerifier;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Hexagonal boundaries: domain and application code stay free of frameworks, JDBC, HTTP and JSON libraries. */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.nexora.reports");

    @Test
    void domainsDependOnNoFrameworkOrIoLibrary() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..",
                        "javax.sql..", "tools.jackson..", "com.fasterxml..", "jakarta..", "java.net.http..",
                        "com.zaxxer..", "org.postgresql..")
                .check(CLASSES);
    }

    @Test
    void domainsDoNotDependOnOuterLayers() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..",
                        "..api..", "..config..", "..security..")
                .check(CLASSES);
    }

    @Test
    void theApplicationLayerDependsOnPortsNotOnAdapters() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..api..", "..config..",
                        "org.springframework..", "java.sql..", "tools.jackson..")
                .check(CLASSES);
    }

    @Test
    void theTranslatorDoesNotExecuteAnything() {
        noClasses().that().resideInAPackage("..translation..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "..execution..")
                .check(CLASSES);
    }

    /** A4-B3: the /2 translator is pure too (the R1 rule's pattern "..translation.." does not match translation2). */
    @Test
    void theV2TranslatorDoesNotExecuteAnything() {
        noClasses().that().resideInAPackage("..translation2..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "..execution..")
                .check(CLASSES);
    }

    /** A4-B4 (v3 §4 bis.1): execution ends with typed rows; it never knows the presentation format or Apache POI. */
    @Test
    void theExecutionLayerDoesNotKnowThePresentation() {
        noClasses().that().resideInAPackage("..execution..")
                .should().dependOnClassesThat().resideInAnyPackage("..render..", "org.apache.poi..",
                        "org.apache.xmlbeans..")
                .check(CLASSES);
    }

    /**
     * A4-B5 (D-B5-8): the mandatory tenancy check of an execution is implemented only by the tenancy adapter, so no
     * permissive verifier can appear anywhere else in production code (test sources are not imported).
     */
    @Test
    void onlyTheTenancyAdapterImplementsTheTenantScopeCheck() {
        classes().that().implement(TenantScopeVerifier.class)
                .should().resideInAPackage("..tenancy.infrastructure..")
                .check(CLASSES);
    }

    /**
     * A4-B6 (D-B6-8): the assembled result is independent of every format, transport and store: it knows neither
     * the presentation (Apache POI, Excel) nor the API, Spring, JSON libraries or persistence.
     */
    @Test
    void theResultIsIndependentOfPresentationApiAndPersistence() {
        noClasses().that().resideInAPackage("..result..")
                .should().dependOnClassesThat().resideInAnyPackage("..render..", "..api..", "..infrastructure..",
                        "..config..", "org.apache.poi..", "org.apache.xmlbeans..", "org.springframework..",
                        "tools.jackson..", "com.fasterxml..", "java.sql..", "javax.sql..")
                .check(CLASSES);
    }

    /** A4 (R4-24): only the Excel presentation adapter may know Apache POI; the engine stays format-independent. */
    @Test
    void onlyTheExcelAdapterUsesApachePoi() {
        noClasses().that().resideOutsideOfPackage("..render..")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.poi..", "org.apache.xmlbeans..")
                .check(CLASSES);
    }

    /**
     * A4-B7 (D-B7-1, precision 2): the presentation knows only the generic result of A4-B6 and the port it
     * implements, never plans, translation, tenancy, credentials, execution adapters, the API or Spring; and the
     * layout decides what is written without knowing Apache POI, which only the writing uses.
     */
    @Test
    void thePresentationKnowsOnlyTheGenericResult() {
        noClasses().that().resideInAPackage("..render..")
                .should().dependOnClassesThat().resideInAnyPackage("..plan..", "..plan2..", "..translation..",
                        "..translation2..", "..tenancy..", "..credentials..", "..execution.infrastructure..",
                        "..api..", "..config..", "..audit..", "org.springframework..", "java.sql..", "javax.sql..")
                .check(CLASSES);
        noClasses().that().resideInAPackage("..render.layout..")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.poi..", "org.apache.xmlbeans..")
                .check(CLASSES);
    }

    /**
     * A4-B8 (D-B8-7, D-B8-8): the run registry knows the generic result and its ports, never presentation, plans,
     * translation, tenancy, credentials, execution adapters, the API, Spring or JSON libraries; and only its
     * infrastructure touches JDBC.
     */
    @Test
    void theRunRegistryKnowsOnlyTheResultAndItsPorts() {
        noClasses().that().resideInAPackage("..run..")
                .should().dependOnClassesThat().resideInAnyPackage("..render..", "..plan..", "..plan2..",
                        "..translation..", "..translation2..", "..tenancy..", "..credentials..",
                        "..execution.infrastructure..", "..api..", "..config..", "..audit..", "org.springframework..",
                        "org.apache.poi..", "tools.jackson..", "com.fasterxml..")
                .check(CLASSES);
        noClasses().that().resideInAPackage("..run..").and().resideOutsideOfPackage("..run.infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "java.nio.file..")
                .check(CLASSES);
    }

    /**
     * A4-B9: the HTTP API knows the services and the domain only: never JDBC, Apache POI, the adapters or the
     * wiring. Only the configuration binds ports to adapters.
     */
    @Test
    void theApiKnowsNeitherPersistenceNorPresentationNorAdapters() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.apache.poi..",
                        "org.apache.xmlbeans..", "..infrastructure..", "..render..", "..config..")
                .check(CLASSES);
    }

    /**
     * A4-B9 (D-B9-1): the run service orchestrates B2 to B8 through ports; it never touches the database, HTTP,
     * POI or an adapter itself, and the tenancy check reaches it only as a port.
     */
    @Test
    void theRunServiceUsesPortsOnly() {
        noClasses().that().resideInAPackage("..execution.application..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "java.net.http..",
                        "org.apache.poi..", "..render..", "..tenancy..")
                .check(CLASSES);
    }
}
