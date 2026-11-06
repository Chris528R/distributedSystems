package escom.chris.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * Enrutamiento inverso (Reverse Proxy) hacia los microservicios internos
 * usando Spring Cloud Gateway Server MVC (Tomcat imperativo, compatible con multipart).
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> ingestionServiceRoute(
            @Value("${app.services.ingestion-url:http://localhost:8081}") String ingestionUrl) {
        return route("ingestion-service")
                .route(path("/api/fft/**"), http(URI.create(ingestionUrl)))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> storageServiceRoute(
            @Value("${app.services.storage-url:http://localhost:8085}") String storageUrl) {
        return route("storage-service")
                .route(path("/api/history/**"), http(URI.create(storageUrl)))
                .build();
    }
}

