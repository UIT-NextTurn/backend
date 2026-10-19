package studyweb.cus.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration for NextTurn real-time virtual queue updates.
 * Supports STOMP protocol over WebSocket and SockJS fallback.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Destination prefixes for messages bound to clients (e.g. /topic/queue/{queueId})
        registry.enableSimpleBroker("/topic", "/queue");
        // Prefix for messages sent from client to server
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Register WebSocket handshake endpoint
        registry.addEndpoint("/ws/queue")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
