package com.nexora.reports.credentials.infrastructure;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Destination policy for enterprise connections (SSRF defense). A connection is allowed only if its host is in an
 * explicit allow-list (exact, case-insensitive match; no wildcards, no DNS-based expansion) and its port in an
 * explicit port list. Link-local and unspecified addresses (cloud metadata endpoints) are refused even if listed.
 * Hosts must be plain names or IP literals: no user info, paths, parameters or list separators that a JDBC driver
 * could interpret.
 */
public final class TargetPolicy {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]{2,39}");

    private final Set<String> hosts;
    private final Set<Integer> ports;

    public TargetPolicy(List<String> allowedHosts, List<Integer> allowedPorts) {
        this.hosts = allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.ports = Set.copyOf(allowedPorts);
    }

    public boolean allows(String host, int port) {
        if (host == null || !(HOST.matcher(host).matches() || IPV6.matcher(host).matches())) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (alwaysForbidden(normalized)) {
            return false;
        }
        return hosts.contains(normalized) && ports.contains(port);
    }

    private static boolean alwaysForbidden(String host) {
        return host.startsWith("169.254.") || host.equals("0.0.0.0") || host.startsWith("fe80:")
                || host.equals("::") || host.equals("metadata.google.internal");
    }
}
