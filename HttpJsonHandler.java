package com.dsmentoring.serverkeeper.core.channels.network.handler;

import com.dsmentoring.serverkeeper.core.channels.DispatcherHandler;
import com.dsmentoring.serverkeeper.core.channels.network.data.RequestDTO;
import com.dsmentoring.serverkeeper.core.channels.network.data.ResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class HttpJsonHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(HttpJsonHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DispatcherHandler requestProcessor;

    public HttpJsonHandler(DispatcherHandler requestProcessor) {
        this.requestProcessor = requestProcessor;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        switch (method) {
            case "POST", "PUT", "PATCH" -> handlePostPutPatch(exchange);
            case "GET" -> handleGet(exchange);
            default -> {
                String msg = "허용되지 않은 메서드: " + method;
                logger.warn(msg);
                sendResponse(exchange, 405, msg);
            }
        }
    }

    private void handlePostPutPatch(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.contains("application/json")) {
            String msg = "잘못된 Content-Type: " + contentType;
            logger.warn(msg);
            sendResponse(exchange, 400, msg);
            return;
        }

        try (InputStream inputStream = exchange.getRequestBody()) {
            RequestDTO requestDto = objectMapper.readValue(inputStream, RequestDTO.class);
            ResponseDTO responseDto = requestProcessor.processRequest(requestDto);
            String responseBody = objectMapper.writeValueAsString(responseDto);
            sendResponse(exchange, 200, responseBody);
        } catch (Exception e) {
            logger.error("[HTTP] JSON 처리 중 오류: {}", e.getMessage(), e);
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Server Error\"}");
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        String responseBody = objectMapper.writeValueAsString("Hello, this is a GET response.");
        sendResponse(exchange, 200, responseBody);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
