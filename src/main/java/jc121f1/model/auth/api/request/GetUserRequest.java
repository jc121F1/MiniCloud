package jc121f1.model.auth.api.request;

import lombok.Builder;

@Builder
public record GetUserRequest(String email, String userId) {
}
