package jc121f1.model.authz;

import jc121f1.model.auth.dao.Session;

/**
 * Identifies an existing user or credential for policy attachment. This is a target
 * identifier, not authentication evidence. Authz must resolve its current identity
 * and account membership through the auth stores before granting access.
 */
public record PrincipalReference(String accountId, String subjectId, Session.SubjectType subjectType) {
}
