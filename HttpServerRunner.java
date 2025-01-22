package com.dsmentoring.serverkeeper.core.channels.network;

import com.dsmentoring.serverkeeper.core.channels.DispatcherHandler;
import com.dsmentoring.serverkeeper.core.channels.ServerRunner;
import com.dsmentoring.serverkeeper.core.manager.components.CommonExecutor;
import com.dsmentoring.serverkeeper.core.channels.network.handler.HttpJsonHandler;
import com.dsmentoring.serverkeeper.core.manager.components.config.CoreConfig.HttpConfig;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;

public class HttpServerRunner implements ServerRunner {
    private static final Logger logger = LoggerFactory.getLogger(HttpServerRunner.class);
    private HttpServer server;
    private final HttpConfig httpConfig;
    private final CommonExecutor commonExecutor;
    private final DispatcherHandler requestProcessor;

    public HttpServerRunner(HttpConfig httpConfig, DispatcherHandler requestProcessor) {
        this.httpConfig = httpConfig;
        this.requestProcessor = requestProcessor;
        this.commonExecutor = CommonExecutor.getInstance();
    }

    @Override
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(httpConfig.getPort()), 0);
        server.createContext("/api", new HttpJsonHandler(requestProcessor));
        server.setExecutor(commonExecutor.addMainExecutor("HTTP"));
        server.start();
        logger.info("[HTTP] 서버가 {} 포트에서 시작되었습니다.", httpConfig.getPort());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(1);
            logger.info("[HTTP] 서버가 종료되었습니다.");
        }
    }
}
