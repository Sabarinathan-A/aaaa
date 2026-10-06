package com.frauddetector.security;

/**
 * Application roles (PRD FR-01, section 30). Role-based access is enforced by
 * {@link AuthFilter#requireRole}.
 */
public enum Role {
    ADMIN,
    CLAIM_OFFICER,
    INVESTIGATOR,
    PROVIDER
}
