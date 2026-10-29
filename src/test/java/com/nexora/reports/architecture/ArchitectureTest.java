package com.nexora.reports.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

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
}
