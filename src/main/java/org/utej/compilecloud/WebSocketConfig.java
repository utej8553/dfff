package org.utej.compilecloud;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CompilerWebSocketHandler compilerWebSocketHandler;

    // Inject the simpler handler
    public WebSocketConfig(CompilerWebSocketHandler compilerWebSocketHandler) {
        this.compilerWebSocketHandler = compilerWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Reverting endpoint to /terminal for the simple handler
        registry.addHandler(compilerWebSocketHandler, "/terminal")
                .setAllowedOrigins("*");
    }
}