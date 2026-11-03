package com.freshlink.gateway.auth;

import com.freshlink.gateway.security.CognitoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * Cognito 클라이언트. 자격은 기본 체인으로 받는다(파드에서는 IRSA 웹 아이덴티티 토큰, PC에서는 AWS_PROFILE).
 * 자격은 처음 호출할 때 찾으므로, 자격이 없어도 gateway는 뜬다.
 */
@Configuration
public class CognitoClientConfig {

    @Bean(destroyMethod = "close")
    CognitoIdentityProviderClient cognitoIdentityProviderClient(CognitoProperties props) {
        return CognitoIdentityProviderClient.builder().region(Region.of(props.region())).build();
    }
}
