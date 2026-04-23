package io.github.lingjiuu.infra.auth;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeName("api_key")
public final class ApiKeyCredential implements AuthCredential {

    private String key;
}
