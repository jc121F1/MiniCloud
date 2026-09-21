package jc121f1.model.auth;

import lombok.Builder;

@Builder
public record AuthenticateRequest(String bearerToken) {

}
