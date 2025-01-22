package com.dsmentoring.serverkeeper.core.manager.components;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 애플리케이션 전반에서 사용되는 공통 ExecutorService를 관리하는 싱글톤 클래스.
 * 메인 스레드와 작업 스레드를 분리하여 관리.
 */
@Getter
public class CommonExecutor {
    private static final Logger logger = LoggerFactory.getLogger(CommonExecutor.class);

    // 모듈별 메인 Executor 관리
    private final Map<String, ExecutorService> mainExecutors = new ConcurrentHashMap<>();

    // 공유된 작업 Executor
    private final ExecutorService workerExecutor;

    private CommonExecutor() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int workerThreads = Math.max(availableProcessors / 2, 1);
        this.workerExecutor = Executors.newFixedThreadPool(workerThreads, new CustomThreadFactory("worker"));

        logger.info("CommonExecutor 초기화 완료: WorkerThreads={}", workerThreads);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownExecutors));
    }

    public static CommonExecutor getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 새로운 모듈을 위해 메인 Executor를 추가합니다.
     *
     * @param moduleName 모듈 이름 (고유 식별자)
     * @return 추가된 메인 Executor
     */
    public synchronized ExecutorService addMainExecutor(String moduleName) {
        if (mainExecutors.containsKey(moduleName)) {
            logger.warn("모듈 [{}]의 메인 Executor가 이미 존재합니다.", moduleName);
            return mainExecutors.get(moduleName);
        }
        ExecutorService mainExecutor = Executors.newSingleThreadExecutor(new CustomThreadFactory("main-module-" + moduleName));
        mainExecutors.put(moduleName, mainExecutor);
        logger.info("모듈 [{}]의 메인 Executor를 추가했습니다.", moduleName);
        return mainExecutor;
    }

    /**
     * 특정 모듈의 메인 Executor를 제거하고 종료합니다.
     *
     * @param moduleName 제거할 모듈의 이름
     */
    public synchronized void removeMainExecutor(String moduleName) {
        ExecutorService mainExecutor = mainExecutors.remove(moduleName);
        if (mainExecutor != null) {
            shutdownExecutor(mainExecutor, "mainExecutor-" + moduleName);
            logger.info("모듈 [{}]의 메인 Executor를 제거했습니다.", moduleName);
        } else {
            logger.warn("모듈 [{}]의 메인 Executor가 존재하지 않습니다.", moduleName);
        }
    }

    /**
     * 모든 ExecutorService를 종료합니다.
     */
    public void shutdownExecutors() {
        logger.info("CommonExecutor 종료 시작");

        // 메인 스레드 풀 종료
        mainExecutors.forEach((name, executor) -> shutdownExecutor(executor, "mainExecutor-" + name));
        mainExecutors.clear();

        // 작업 스레드 풀 종료
        shutdownExecutor(workerExecutor, "workerExecutor");

        logger.info("CommonExecutor 종료 완료");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                logger.warn("{}가 60초 내에 종료되지 않아 강제 종료 시도", name);
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    logger.error("{}가 여전히 종료되지 않았습니다", name);
                }
            }
            logger.info("{} 상태: {}", name, executor.isShutdown() ? "Shutdown" : "Running");
        } catch (InterruptedException e) {
            logger.error("{} 종료 중 인터럽트 발생", name, e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class Holder {
        private static final CommonExecutor INSTANCE = new CommonExecutor();
    }

    /**
     * 스레드 이름에 기본 이름과 카운터를 설정하는 커스텀 ThreadFactory.
     */
    private static class CustomThreadFactory implements ThreadFactory {
        private final String baseName;
        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        private final AtomicInteger counter = new AtomicInteger(0);

        public CustomThreadFactory(String baseName) {
            this.baseName = baseName;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = defaultFactory.newThread(r);
            thread.setName(baseName + "-thread-" + counter.getAndIncrement());
            thread.setDaemon(true); // 선택 사항: 데몬 스레드로 설정
            return thread;
        }
    }
}
