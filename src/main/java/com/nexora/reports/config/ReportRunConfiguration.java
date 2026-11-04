package com.nexora.reports.config;

import com.nexora.reports.execution.application.ReportRunDocuments;
import com.nexora.reports.execution.application.ReportRunService;
import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.application.port.ReportPlanSource;
import com.nexora.reports.execution.application.port.SourceAvailability;
import com.nexora.reports.execution.application.port.TenantScopes;
import com.nexora.reports.execution.infrastructure.ConnectionPools;
import com.nexora.reports.execution.infrastructure.PostgresReadOnlyExecutor;
import com.nexora.reports.plan.infrastructure.BoundedHttp;
import com.nexora.reports.plan.infrastructure.PlanDocumentReader;
import com.nexora.reports.plan.infrastructure.SemanticProperties;
import com.nexora.reports.plan.infrastructure.ServiceTokenProvider;
import com.nexora.reports.plan2.domain.PlanV2Validator;
import com.nexora.reports.plan2.domain.ReportsCapabilities;
import com.nexora.reports.plan2.domain.ReportsCeilings;
import com.nexora.reports.plan2.domain.Sha256;
import com.nexora.reports.plan2.infrastructure.ClasspathContractSchemas;
import com.nexora.reports.plan2.infrastructure.JdkSha256;
import com.nexora.reports.plan2.infrastructure.RunContractSchemas;
import com.nexora.reports.plan2.infrastructure.SemanticReportPlanSource;
import com.nexora.reports.plan2.infrastructure.StrictJsonTreeReader;
import com.nexora.reports.render.xlsx.PoiTemporaryFiles;
import com.nexora.reports.render.xlsx.XlsxRenderer;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.ResultAssembler;
import com.nexora.reports.run.domain.StorageLimits;
import com.nexora.reports.run.infrastructure.ArtifactStaging;
import com.nexora.reports.run.infrastructure.JdbcArtifactDeliveries;
import com.nexora.reports.run.infrastructure.JdbcRunMaintenance;
import com.nexora.reports.run.infrastructure.JdbcRunReader;
import com.nexora.reports.run.infrastructure.JdbcRunStore;
import com.nexora.reports.run.infrastructure.StagingDirectory;
import com.nexora.reports.tenancy.application.TenancyRegistrationService;
import com.nexora.reports.tenancy.infrastructure.JdbcTenancyRegistry;
import com.nexora.reports.tenancy.infrastructure.PostgresTenancyCapture;
import com.nexora.reports.tenancy.infrastructure.RegisteredTenantScopes;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * A4-B9: wiring of the {@code /2} report runs (B2 to B8) and of the tenancy endpoints. Only this package knows the
 * adapters; the service sees ports. The run registry and the tenancy registry live in Reports' own database (the
 * Spring {@link DataSource}, migrated by Flyway); enterprise sources are only ever read through the read-only pools.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReportRunProperties.class, StorageProperties.class, PresentationProperties.class})
@EnableScheduling
public class ReportRunConfiguration {

    @Bean
    StrictJsonTreeReader strictJsonTreeReader() {
        return new StrictJsonTreeReader();
    }

    @Bean
    Sha256 sha256() {
        return new JdkSha256();
    }

    /** A4-B10: the ceilings of a report plan, shared by the validator and the capabilities descriptor. */
    @Bean
    ReportsCeilings reportsCeilings() {
        return ReportsCeilings.DEFAULTS;
    }

    @Bean
    PlanV2Validator planV2Validator(StrictJsonTreeReader reader, Sha256 sha256, ReportsCeilings ceilings) {
        return new PlanV2Validator(ClasspathContractSchemas.load(reader), ceilings, sha256);
    }

    /**
     * A4-B10 (D-A4-8): the {@code nexora-capabilities/1} of Reports, generated from the constants and ceilings the
     * validator enforces and checked once against the real schema: a descriptor that does not validate stops
     * Reports from starting, so an invalid one is never served.
     */
    @Bean
    ReportsCapabilities reportsCapabilities(StrictJsonTreeReader reader, ReportsCeilings ceilings,
                                            ReportRunProperties runs) {
        ReportsCapabilities capabilities = ReportsCapabilities.of(runs.reportsVersion(), ceilings);
        RunContractSchemas.capabilities(reader).firstError(capabilities.document()).ifPresent(error -> {
            throw new IllegalStateException("the capabilities descriptor does not satisfy nexora-capabilities/1 at "
                    + error);
        });
        return capabilities;
    }

    @Bean
    ReportRunDocuments reportRunDocuments(StrictJsonTreeReader reader) {
        return new ReportRunDocuments(RunContractSchemas.reportRun(reader), RunContractSchemas.reportArtifact(reader));
    }

    @Bean
    ReportPlanSource reportPlanSource(SemanticProperties semantic, ServiceTokenProvider tokens,
                                      PlanDocumentReader problems, StrictJsonTreeReader reader) {
        BoundedHttp http = new BoundedHttp(semantic.connectTimeout(), semantic.readTimeout(),
                semantic.maxResponseBytes());
        return new SemanticReportPlanSource(semantic.baseUrl(), http, tokens, problems, reader);
    }

    // ------------------------------------------------------------------ tenancy (A4-B5, endpoints in B9)

    @Bean
    TenancyRegistrationService tenancyRegistrationService(DataSource reportsDatabase, ConnectionPools pools,
                                                          CredentialProvider credentials, Sha256 sha256,
                                                          Clock clock) {
        return new TenancyRegistrationService(new JdbcTenancyRegistry(reportsDatabase),
                new PostgresTenancyCapture(pools), credentials, sha256, clock);
    }

    @Bean
    TenantScopes tenantScopes(TenancyRegistrationService registrations, Clock clock) {
        return new RegisteredTenantScopes(registrations, clock);
    }

    // ------------------------------------------------------------------ storage (A4-B8)

    @Bean
    StorageLimits storageLimits(StorageProperties storage) {
        return storage.limits();
    }

    /** The staging directory, swept at startup: nothing is being written yet, so anything there is a leftover. */
    @Bean
    StagingDirectory stagingDirectory(StorageProperties storage) {
        try {
            StagingDirectory staging = new StagingDirectory(new ArtifactStaging(storage.staging()));
            staging.sweep(Duration.ZERO);
            return staging;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare nexora.reports.storage.directory", e);
        }
    }

    @Bean
    JdbcRunStore runStore(DataSource reportsDatabase, StorageLimits limits) {
        return new JdbcRunStore(reportsDatabase, limits);
    }

    @Bean
    JdbcRunReader runReader(DataSource reportsDatabase, StorageLimits limits) {
        return new JdbcRunReader(reportsDatabase, limits);
    }

    @Bean
    JdbcRunMaintenance runMaintenance(DataSource reportsDatabase) {
        return new JdbcRunMaintenance(reportsDatabase);
    }

    @Bean
    JdbcArtifactDeliveries artifactDeliveries(DataSource reportsDatabase) {
        return new JdbcArtifactDeliveries(reportsDatabase);
    }

    // ------------------------------------------------------------------ presentation (A4-B7)

    /**
     * POI's temporary files go to Reports' storage (B7-O3); the strategy is global, so it is fixed here, once. Every
     * report is presented with the configured theme (EX-19, D-XL-3).
     */
    @Bean
    XlsxRenderer reportRenderer(StorageProperties storage, ReportRunProperties runs,
                                PresentationProperties presentation) {
        try {
            PoiTemporaryFiles.use(storage.poiFiles());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare the POI temporary directory", e);
        }
        return new XlsxRenderer(runs.maxConcurrentRenders(), storage.poiFiles(), presentation.theme());
    }

    // ------------------------------------------------------------------ the service

    @Bean
    ReportRunService reportRunService(ReportPlanSource semantic, SourceAvailability availability,
                                      CredentialProvider credentials, PlanV2Validator validator, Sha256 sha256,
                                      PostgresReadOnlyExecutor executor,
                                      TenantScopes tenantScopes, XlsxRenderer renderer, StagingDirectory staging,
                                      JdbcRunStore store, JdbcRunReader reader, JdbcArtifactDeliveries deliveries,
                                      ReportRunDocuments documents, ReportRunProperties runs,
                                      StorageProperties storage, ExecutionProperties execution,
                                      ReportsCeilings ceilings, Clock clock) {
        ReportRunService.Settings settings = new ReportRunService.Settings(runs.maxDuration(), execution.limits(),
                com.nexora.reports.execution.domain.RunBudget.DEFAULT_MAX_ESTIMATED_BYTES, AssemblyLimits.DEFAULTS,
                new RenderLimits(storage.limits().maxFileBytes(), RenderLimits.DEFAULTS.rowWindow(),
                        RenderLimits.DEFAULTS.acquireTimeout()),
                ceilings.maxPlans(), runs.reportsVersion(), runs.maxConcurrentDownloads());
        return new ReportRunService(semantic, availability, credentials, validator, sha256, new PlanV2Translator(),
                executor, tenantScopes, new ResultAssembler(), renderer, staging, store, reader, reader, deliveries, documents,
                settings, clock, UUID::randomUUID);
    }

    // ------------------------------------------------------------------ housekeeping (D-B9-9)

    @Bean
    RunHousekeeping runHousekeeping(JdbcRunMaintenance maintenance, StagingDirectory staging,
                                    ReportRunProperties runs) {
        return new RunHousekeeping(maintenance, staging, runs.housekeepingBatch(), runs.stagingMaxAge());
    }
}
