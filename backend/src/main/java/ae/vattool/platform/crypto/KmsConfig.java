package ae.vattool.platform.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class KmsConfig {

    /** The local key is for development. Starting production on it is refused outright, not warned about. */
    @Bean
    Kms kms(@Value("${app.crypto.kms:local}") String provider,
            @Value("${app.crypto.local-master-key:}") String localKey,
            @Value("${app.crypto.aws-key-id:}") String awsKeyId,
            @Value("${app.crypto.aws-region:me-central-1}") String awsRegion,
            @Value("${app.crypto.aws-endpoint:}") String awsEndpoint,
            Environment env) {
        if ("aws".equals(provider)) {
            return new AwsKms(awsKeyId, awsRegion, awsEndpoint);
        }
        if (env.matchesProfiles("prod")) {
            throw new IllegalStateException("The local key service cannot run in production. Set app.crypto.kms to a real provider.");
        }
        return new LocalKms(localKey);
    }
}
