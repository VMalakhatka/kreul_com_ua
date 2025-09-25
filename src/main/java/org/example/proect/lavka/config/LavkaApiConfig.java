package org.example.proect.lavka.config;

import org.example.proect.lavka.property.LavkaApiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class LavkaApiConfig {

    @Bean(name = "lavkaRestTemplateBasic")
    public RestTemplate lavkaRestTemplateBasic(LavkaApiProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getTimeoutMs());
        factory.setReadTimeout(props.getTimeoutMs());

        var rt = new RestTemplate(factory);
        rt.getInterceptors().add((request, body, execution) -> {
            String creds = props.getUser() + ":" + props.getAppPass();
            String basic = java.util.Base64.getEncoder()
                    .encodeToString(creds.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            request.getHeaders().add("Authorization", "Basic " + basic);
            return execution.execute(request, body);
        });
        return rt;
    }

    @Bean(name = "lavkaRestTemplateToken")
    public RestTemplate lavkaRestTemplateToken(LavkaApiProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getTimeoutMs());
        factory.setReadTimeout(props.getTimeoutMs());

        var rt = new RestTemplate(factory);
        rt.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().add("X-Auth-Token", props.getToken());
            return execution.execute(request, body);
        });
        return rt;
    }
}