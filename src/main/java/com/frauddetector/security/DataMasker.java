package com.frauddetector.security;

/**
 * Sensitive-data masking (PRD section 30). Roles that review claims (ADMIN,
 * CLAIM_OFFICER, INVESTIGATOR) see full patient identity; everyone else
 * (PROVIDER) sees masked values, e.g. {@code "John Doe" -> "J*** D**"} and
 * {@code "INS-1001" -> "****1001"}.
 */
public final class DataMasker {

    private DataMasker() {
    }

    /** True when {@code role} may see unmasked patient identity. */
    public static boolean canViewSensitive(Role role) {
        return role == Role.ADMIN || role == Role.CLAIM_OFFICER || role == Role.INVESTIGATOR;
    }

    /** Keep the first letter of each word, mask the rest. */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part.charAt(0)).append("*".repeat(Math.max(part.length() - 1, 2)));
        }
        return sb.toString();
    }

    /** Keep only the last four characters of an identifier. */
    public static String maskId(String id) {
        if (id == null || id.isBlank()) {
            return id;
        }
        if (id.length() <= 4) {
            return "*".repeat(id.length());
        }
        return "****" + id.substring(id.length() - 4);
    }

    /** Mask the host part of an IPv4/IPv6 address for display. */
    public static String maskIp(String ip) {
        if (ip == null) {
            return null;
        }
        int dot = ip.lastIndexOf('.');
        if (dot > 0) {
            return ip.substring(0, dot) + ".xxx";
        }
        int colon = ip.lastIndexOf(':');
        return colon > 0 ? ip.substring(0, colon) + ":xxxx" : "xxx";
    }
}
