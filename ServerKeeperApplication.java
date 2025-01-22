package com.dsmentoring.serverkeeper;

import com.dsmentoring.serverkeeper.core.ApplicationContext;
import com.dsmentoring.serverkeeper.core.channels.ServerRunner;
import com.dsmentoring.serverkeeper.core.manager.ModuleManagerInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerKeeperApplication {
    private static final Logger logger = LoggerFactory.getLogger(ServerKeeperApplication.class);

    public static void main(String[] args) {
        try {
            ApplicationContext context = new ApplicationContext("setting.yml");
            ModuleManagerInterface moduleManager = context.getModuleManager();

            // 1) 모듈 시작
            moduleManager.startAll();
            logger.info("모든 모듈 실행 시작.");

            // 2) 서버 시작
            for (ServerRunner runner : context.getServerRunners()) {
                try {
                    runner.start();
                } catch (Exception e) {
                    logger.error("[Main] 서버 시작 중 오류 발생: {}", e.getMessage(), e);
                }
            }

            // 3) 종료 훅
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("애플리케이션 종료 신호 수신. 자원 정리 시작...");
                for (ServerRunner runner : context.getServerRunners()) {
                    runner.stop();
                }
                moduleManager.shutdown();
                logger.info("자원 정리 완료. 애플리케이션 종료.");
            }));

            // 메인 스레드 유지
            Thread.currentThread().join();

        } catch (Exception e) {
            logger.error("애플리케이션 실행 중 오류 발생: {}", e.getMessage(), e);
        }
    }
}
