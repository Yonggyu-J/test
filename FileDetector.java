package com.dsmentoring.serverkeeper.modules.action.event.FileDetector;

import com.dsmentoring.serverkeeper.common.data.ModuleMetadata;
import com.dsmentoring.serverkeeper.common.enums.ModuleEnum;
import com.dsmentoring.serverkeeper.common.enums.ModuleStateEnum;
import com.dsmentoring.serverkeeper.common.enums.RequestEnum;
import com.dsmentoring.serverkeeper.common.snippet.mail.MailData;
import com.dsmentoring.serverkeeper.core.framework.AbstractModule;
import com.dsmentoring.serverkeeper.modules.action.event.EventConfig.FileDetectorConfig;
import com.dsmentoring.serverkeeper.core.manager.components.CommonExecutor;
import com.dsmentoring.serverkeeper.common.snippet.mail.MailSender;
import com.dsmentoring.serverkeeper.modules.action.event.EventModuleInterface;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.dsmentoring.serverkeeper.common.enums.ModuleEnum.Engine.FILE_DETECTOR;
import static com.dsmentoring.serverkeeper.common.enums.ModuleStateEnum.RUNNING;
import static com.dsmentoring.serverkeeper.common.enums.ModuleStateEnum.TERMINATED;

@ModuleMetadata(type = ModuleEnum.EVENT, name = ModuleEnum.Engine.FILE_DETECTOR, request = {RequestEnum.SMTP})
public class FileDetector extends AbstractModule implements EventModuleInterface {
    private FileDetectorConfig fileDetectorConfig;
    private volatile boolean terminateRequested = false;
    private volatile boolean stopRequested = false;  // ★ 추가: 일시 중단 신호
    private volatile ModuleStateEnum moduleStateEnum = ModuleStateEnum.CREATED;
    private static final Logger logger = LoggerFactory.getLogger(FileDetector.class);
    private final Map<String, Future<?>> workerFutures = new ConcurrentHashMap<>();
    private ExecutorService workerExecutor;
    private FileHasher fileHasher;
    private MailSender mailSender;
    private List<String> receivers;
    private String sender;

    @Override
    public void initialize(HashMap<String, Object> initialMap) throws Exception {
        updateConfig(initialMap);
        this.workerExecutor = CommonExecutor.getInstance().getWorkerExecutor();
        this.mailSender = MailSender.getInstance();
        this.fileHasher = new FileHasher();
        this.moduleStateEnum = ModuleStateEnum.INITIALIZED;
        logger.info("FileDetector 초기화 완료: {}", fileDetectorConfig);
    }

    /**
     * 설정을 변경하는 메서드.
     */
    @Override
    public void updateConfig(HashMap<String, Object> initialMap) {
        if (moduleStateEnum != TERMINATED && moduleStateEnum != RUNNING) {
            this.fileDetectorConfig = (FileDetectorConfig) initialMap.get("config");
            this.receivers = (List<String>) initialMap.get("receivers");
            this.sender = (String) initialMap.get("sender");
            logger.info("설정 업데이트");
        } else {
            logger.warn("모듈 설정 업데이트 불가. 현재 상태: {}", moduleStateEnum);
        }
    }

    @Override
    public void run() throws Exception {
        logger.info("FileDetector 메인 루프 시작");
        try {
            moduleStateEnum = ModuleStateEnum.RUNNING;

            while (!Thread.currentThread().isInterrupted() && !terminateRequested && !stopRequested) {
                try {
                    long startTime = System.currentTimeMillis();
                    CompletableFuture<List<DirectoryChange>> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            return fileHasher.hashDirectories(
                                    Arrays.stream(fileDetectorConfig.getTargetDirectory()).toList(),
                                    fileDetectorConfig.getBasePath()
                            );
                        } catch (IOException | NoSuchAlgorithmException e) {
                            throw new CompletionException(e);
                        }
                    }, workerExecutor);

                    List<DirectoryChange> changes = future.get();
                    long endTime = System.currentTimeMillis();

                    boolean hasChanges = false;
                    List<Map<String, Object>> logBoxes = new ArrayList<>();
                    for (DirectoryChange change : changes) {
                        int totalChanges = change.getAdded().size() + change.getDeleted().size() + change.getModified().size();
                        if (totalChanges > 0) {
                            logger.info("소요 시간 : " + ((endTime - startTime) / 1000.0) + "s");
                            logger.info(change.getDirectoryPath() + "에서 변경사항이 발견됐습니다.");
                            Map<String, Object> logBox = new HashMap<>();

                            logBox.put("subHeader", change.getDirectoryPath());
                            if (!change.getAdded().isEmpty()) {
                                logBox.put("added (" + change.getAdded().size() + ")", change.getAdded());
                            }
                            if (!change.getModified().isEmpty()) {
                                logBox.put("modified (" + change.getModified().size() + ")", change.getModified());
                            }
                            if (!change.getDeleted().isEmpty()) {
                                logBox.put("deleted (" + change.getDeleted().size() + ")", change.getDeleted());
                            }
                            logBoxes.add(logBox);
                            hasChanges = true;
                        }
                    }

                    if (hasChanges) {
                        CompletableFuture.runAsync(() -> {
                            try {
                                this.mailSender.sendTemplateEmail(
                                        "email-template",
                                        "디렉토리 내 접근이 감지되었습니다.",
                                        this.sender,
                                        this.receivers,
                                        MailData.builder()
                                                .headerTitle("Server Keeper 처리 결과 안내")
                                                .contentHeader("지정 디렉토리 내 파일 변경 감지")
                                                .contentParagraph("")
                                                .logBoxes(logBoxes)
                                                .build()
                                );
                                logger.info("메일 전송 완료: {}", receivers);
                            } catch (Exception e) {
                                logger.error("메일 전송 중 오류 발생", e);
                            }
                        }, workerExecutor);
                    }

                    // 설정된 인터벌만큼 대기
                    TimeUnit.SECONDS.sleep(fileDetectorConfig.getInterval());

                } catch (InterruptedException e) {
                    logger.warn("FileDetector 실행 중 인터럽트 발생", e);
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    logger.error("hashDirectories 실행 중 오류 발생", e.getCause());
                }
            }

            // 루프가 중단된 이유에 따라 상태 설정
            if (stopRequested) {
                logger.info("FileDetector가 stop 요청에 의해 중단되었습니다.");
                moduleStateEnum = ModuleStateEnum.STOPPED;
            } else if (terminateRequested) {
                logger.info("FileDetector가 terminate 요청에 의해 중단되었습니다.");
                moduleStateEnum = ModuleStateEnum.TERMINATED;
            }
        } finally {
        }
    }

    @Override
    protected Logger getLogger() {
        return this.logger;
    }

    @Override
    public ModuleEnum.Engine getModuleEngine() {
        return FILE_DETECTOR;
    }

    @Getter
    @Setter
    public static class DirectoryChange {
        private String directoryPath;
        private List<String> added;
        private List<String> deleted;
        private List<String> modified;

        public DirectoryChange(String directoryPath) {
            this.directoryPath = directoryPath;
            this.added = new ArrayList<>();
            this.deleted = new ArrayList<>();
            this.modified = new ArrayList<>();
        }
    }
}
