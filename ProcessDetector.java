package com.dsmentoring.serverkeeper.modules.action.batch.ProcessDetector;

import com.dsmentoring.serverkeeper.common.data.ModuleMetadata;
import com.dsmentoring.serverkeeper.common.enums.ModuleEnum;
import com.dsmentoring.serverkeeper.common.enums.ModuleStateEnum;
import com.dsmentoring.serverkeeper.common.enums.RequestEnum;
import com.dsmentoring.serverkeeper.common.snippet.mail.MailData;
import com.dsmentoring.serverkeeper.core.manager.components.CommonExecutor;
import com.dsmentoring.serverkeeper.core.framework.AbstractModule;
import com.dsmentoring.serverkeeper.modules.action.batch.BatchConfig.ProcessDetectorConfig;
import com.dsmentoring.serverkeeper.modules.action.batch.BatchModuleInterface;
import com.dsmentoring.serverkeeper.common.snippet.mail.MailSender;
import com.dsmentoring.serverkeeper.modules.utils.linux.command.CommandInterface;
import com.dsmentoring.serverkeeper.modules.utils.linux.command.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;

import static com.dsmentoring.serverkeeper.common.enums.ModuleEnum.Engine.PROCESS_DETECTOR;
import static com.dsmentoring.serverkeeper.common.enums.ModuleStateEnum.RUNNING;
import static com.dsmentoring.serverkeeper.common.enums.ModuleStateEnum.TERMINATED;
import static com.dsmentoring.serverkeeper.common.enums.ModuleStateEnum.STOPPED;

@ModuleMetadata(type = ModuleEnum.BATCH, name = PROCESS_DETECTOR, request = {RequestEnum.SMTP})
public class ProcessDetector extends AbstractModule implements BatchModuleInterface {
    private ProcessDetectorConfig processDetectorConfig;
    private volatile boolean terminateRequested = false;
    private volatile boolean stopRequested = false;
    private volatile ModuleStateEnum moduleStateEnum = ModuleStateEnum.CREATED;
    private static final Logger logger = LoggerFactory.getLogger(ProcessDetector.class);
    private final Map<String, Future<?>> workerFutures = new ConcurrentHashMap<>();
    private ExecutorService workerExecutor;
    private CommandInterface commandInterface;
    private MailSender mailSender;
    private List<String> receivers;
    private String sender;

    @Override
    public void initialize(HashMap<String, Object> initialMap) throws Exception {
        updateConfig(initialMap);
        this.workerExecutor = CommonExecutor.getInstance().getWorkerExecutor();
        this.mailSender = MailSender.getInstance();
        this.commandInterface = new Commands();
        this.moduleStateEnum = ModuleStateEnum.INITIALIZED;
        logger.info("ProcessDetector 초기화 완료: {}", processDetectorConfig);
    }

    /**
     * 정지 상태에서 새로운 설정을 적용하는 메서드
     * (예: ModuleManager에서 stop 후 config 업데이트 후 다시 start)
     */
    public void updateConfig(HashMap<String, Object> initialMap) {
        if (moduleStateEnum != TERMINATED && moduleStateEnum != RUNNING) {
            this.processDetectorConfig = (ProcessDetectorConfig) initialMap.get("config");
            this.receivers = (List<String>) initialMap.get("receivers");
            this.sender = (String) initialMap.get("sender");
            logger.info("ProcessDetector 설정 업데이트");
        } else {
            logger.warn("모듈이 STOPPED 상태가 아니므로 설정 업데이트 불가. 현재 상태: {}", moduleStateEnum);
        }
    }

    @Override
    public void run() throws Exception {
        logger.info("ProcessDetector 메인 루프 시작");
        try {
            moduleStateEnum = RUNNING;

            while (!Thread.currentThread().isInterrupted() && !terminateRequested && !stopRequested) {
                List<ProcessDataEntry> entries = processDetectorConfig.getProcessDataEntries();
                for (ProcessDataEntry entry : entries) {
                    if (terminateRequested || stopRequested) {
                        break;
                    }

                    // 이미 모니터링 중인 프로세스인지 확인
                    if (isProcessBeingMonitored(entry.getProcessName())) {
                        logger.warn("이미 모니터링 중인 프로세스: {}", entry.getProcessName());
                        continue;
                    }

                    // 프로세스 모니터링 & 재시작 작업 쓰레드 제출
                    Future<?> future = workerExecutor.submit(() -> monitorAndRestartProcess(entry));
                    workerFutures.put(entry.getProcessName(), future);
                }

                // 완료된 Future 제거
                workerFutures.entrySet().removeIf(entry -> entry.getValue().isDone() || entry.getValue().isCancelled());

                try {
                    TimeUnit.SECONDS.sleep(processDetectorConfig.getInterval());

                } catch (InterruptedException ie) {
                    logger.info("ProcessDetector 메인 루프가 인터럽트로 인해 종료됩니다.");
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 루프가 중단된 이유에 따라 상태 설정
            if (stopRequested && !terminateRequested) {
                logger.info("ProcessDetector가 stop 요청에 의해 중단되었습니다.");
                moduleStateEnum = STOPPED;
            } else if (terminateRequested) {
                logger.info("ProcessDetector가 terminate 요청에 의해 중단되었습니다.");
                moduleStateEnum = TERMINATED;
            }
        } finally {

        }
    }

    @Override
    protected Logger getLogger() {
        return this.logger;
    }

    /**
     * 이미 모니터링 중인 프로세스인지 확인
     */
    private boolean isProcessBeingMonitored(String processName) {
        Future<?> future = workerFutures.get(processName);
        return future != null && !future.isDone() && !future.isCancelled();
    }

    /**
     * 프로세스를 모니터링하다가 중단이면 재시작하는 로직
     */
    private void monitorAndRestartProcess(ProcessDataEntry entry) {
        java.lang.Process process = null;
        BufferedReader reader = null;
        try {
            // 프로세스가 이미 돌고 있는지 확인
            if (isProcessRunning(entry.getProcessName())) {
                logger.info("프로세스 정상 작동 중: {}", entry.getProcessName());
                return;
            }

            logger.warn("프로세스({}) 중단 감지. 재실행 시도.", entry.getProcessName());
            List<String> command = new ArrayList<>(List.of("bash", entry.getResourcePath()));
            command.addAll(entry.getArgument());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            process = processBuilder.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            int exitCode = finished ? process.exitValue() : -1;
            logger.info("스크립트 실행 종료: {}, 종료 코드: {}", entry.getProcessName(), exitCode);

            if (exitCode != 0) {
                logger.error("스크립트 실행 중 오류 발생: {}", entry.getProcessName());
            }

            // 로그 수집 및 이메일 전송
            //sendProcessTerminationEmail(entry, exitCode);

        } catch (InterruptedException ie) {
            logger.info("monitorAndRestartProcess가 인터럽트로 인해 중단됩니다: {}", entry.getProcessName());
            Thread.currentThread().interrupt(); // 인터럽트 상태 복원
        } catch (Exception e) {
            logger.error("프로세스 감지 중 오류 발생: {}", entry.getProcessName(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    logger.error("BufferedReader 닫는 중 오류 발생: {}", e.getMessage(), e);
                }
            }
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * pgrep 명령어로 프로세스가 실행 중인지 확인
     */
    private boolean isProcessRunning(String processName) throws IOException, InterruptedException {
        ProcessBuilder checkBuilder = new ProcessBuilder("pgrep", "-x", processName);
        Process checkProcess = checkBuilder.start();
        boolean isRunning = checkProcess.waitFor(10, TimeUnit.SECONDS) && checkProcess.exitValue() == 0;
        checkProcess.destroy();
        return isRunning;
    }

    /**
     * 프로세스 중단 감지 시 이메일 전송
     */
    private void sendProcessTerminationEmail(ProcessDataEntry entry, int exitCode) {
        CompletableFuture.runAsync(() -> {
            try {
                List<Map<String, Object>> logBoxes = new ArrayList<>();
                // 예시) 실행된 로그 명령어 결과 수집
                if (!processDetectorConfig.getTargetLogCommands().isEmpty()) {
                    Map<String, Object> logBox = new HashMap<>();
                    List<String> resultList = new ArrayList<>();
                    logBox.put("subHeader", entry.getProcessName());
                    for (String execLog : processDetectorConfig.getTargetLogCommands()) {
                        String logResult = execLog + " : " + commandInterface.runCommand(execLog + " " + entry.getProcessName());
                        logger.info(logResult);
                        resultList.add(logResult);
                    }
                    logBox.put("logs", resultList);
                    logBoxes.add(logBox);
                }

                mailSender.sendTemplateEmail(
                        "email-template",
                        "프로세스 중단 감지",
                        this.sender,
                        this.receivers,
                        MailData.builder()
                                .headerTitle("Server Keeper 알림")
                                .contentHeader("프로세스 중단 감지")
                                .contentParagraph("프로세스 " + entry.getProcessName() + "가 중단되어 재실행되었습니다.")
                                .logBoxes(logBoxes.isEmpty() ? null : logBoxes)
                                .build()
                );
                logger.info("이메일 전송 완료: {}", receivers);
            } catch (Exception e) {
                logger.error("이메일 전송 중 오류 발생: {}", e.getMessage(), e);
            }
        }, workerExecutor);
    }

    @Override
    public ModuleStateEnum getStatus() {
        return this.moduleStateEnum;
    }

    @Override
    public ModuleEnum.Engine getModuleEngine() {
        return PROCESS_DETECTOR;
    }
}
