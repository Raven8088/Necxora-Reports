package com.nexora.reports.execution.application.port;

import com.nexora.reports.credentials.domain.SourceTarget;

import java.util.Optional;
import java.util.UUID;

/**
 * B10-O3 (D-O3-1): whether a read-only connection is configured for a data source of a tenant, and to which
 * destination, <b>without resolving its secret</b>. A run asks this before contacting Semantic (so that an
 * unconfigured source is answered without leaving a run) and resolves the credentials themselves
 * ({@link CredentialProvider}) only right before a read, comparing both destinations.
 * <p>
 * Like {@link CredentialProvider}, the pair {@code (tenantId, dataSourceId)} is the only key, and implementations
 * apply the destination policy (allowed hosts and ports) before answering: a forbidden destination is an
 * {@code ExecutionException} with {@code DATA_SOURCE_TARGET_NOT_ALLOWED}, exactly as when resolving.
 */
public interface SourceAvailability {

    Optional<SourceTarget> configured(UUID tenantId, UUID dataSourceId);
}
