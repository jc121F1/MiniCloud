package jc121f1.model.auth.dao;

import lombok.Builder;

@Builder
public record AuthenticatedSession(String accountId, String subjectId, Session.SubjectType subjectType) {
}
