package com.dsmentoring.serverkeeper.core.manager;

import com.dsmentoring.serverkeeper.common.data.ModuleMetadata;
import com.dsmentoring.serverkeeper.common.enums.ModuleEnum;
import com.dsmentoring.serverkeeper.common.enums.ModuleEnum.Engine;
import com.dsmentoring.serverkeeper.common.enums.ModuleStateEnum;
import com.dsmentoring.serverkeeper.common.enums.RequestEnum;
import com.dsmentoring.serverkeeper.common.snippet.mail.MailSender;
import com.dsmentoring.serverkeeper.core.manager.components.ConfigLoader;
import com.dsmentoring.serverkeeper.core.manager.components.CommonExecutor;
import com.dsmentoring.serverkeeper.core.framework.ModuleInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 여러 모듈을 로딩하고 실행/중지하며, 생명주기를 관리하는 클래스.
 * 변경된 ModuleEnum, ModuleStateEnum 적용.
 */
public class ModuleManager implements ModuleManagerInterface {
    private static final Logger logger = LoggerFactory.getLogger(ModuleManager.class);
    private final ConfigLoader configLoader;
    private final List<ModuleInterface> modules = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<ModuleInterface, ModuleStateEnum> moduleStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ModuleInterface, Future<?>> moduleFutures = new ConcurrentHashMap<>();
    private final CommonExecutor commonExecutor;

    public ModuleManager(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.commonExecutor = CommonExecutor.getInstance();
        MailSender.initialize(configLoader.getConfigs().getCore().getSmtp());
        loadModules();
    }

    /**
     * 활성화된 모듈 이름 목록 추출
     */
    private Set<String> getEnabledModuleNames() {
        return Optional.ofNullable(configLoader.getConfigs().getCore().getActiveModules())
                .orElse(Collections.emptyList())
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    /**
     * ServiceLoader를 통해 모듈을 찾아, 활성화 목록에 있는 모듈만 등록
     */
    private void loadModules() {
        Set<String> activeModulesNames = getEnabledModuleNames();
        ServiceLoader<ModuleInterface> loader = ServiceLoader.load(ModuleInterface.class);

        logger.info("ServiceLoader로 모듈을 스캔 중... 활성화된 모듈 수: {}", activeModulesNames.size());

        try {
            for (ModuleInterface module : loader) {
                ModuleMetadata metadata = module.getClass().getAnnotation(ModuleMetadata.class);
                if (metadata == null) {
                    logger.warn("모듈에 @ModuleMetadata가 누락됨: {}", module.getClass().getName());
                    continue;
                }

                String moduleName = metadata.name().name().toLowerCase();
                ModuleEnum moduleType = metadata.type();
                Engine engine = metadata.name();
                logger.info("발견된 모듈: {}, TypeDesc=[{}], EngineDesc=[{}]",
                        module.getClass().getSimpleName(),
                        moduleType.getDescription(),
                        engine.getEngineDescription()
                );

                if (activeModulesNames.contains(moduleName)) {
                    addModule(module, moduleName);
                    activeModulesNames.remove(moduleName);
                    logger.info("활성 모듈 등록 완료: {}", module.getClass().getName());
                } else {
                    logger.info("비활성 모듈 스킵: {}", module.getClass().getName());
                }
            }
        } catch (ServiceConfigurationError e) {
            logger.error("모듈 로드 중 오류 발생: {}", e.getMessage(), e);
        }

        if (!activeModulesNames.isEmpty()) {
            logger.warn("다음 활성 모듈을 찾지 못했습니다: {}", activeModulesNames);
        } else {
            logger.info("모든 활성 모듈 스캔/등록 완료.");
        }
        logger.info("최종 등록된 활성 모듈 수: {}", modules.size());
    }

    /**
     * 모듈 등록
     *
     * @param module     등록할 모듈
     * @param moduleName 모듈의 고유 이름
     */
    @Override
    public void addModule(ModuleInterface module, String moduleName) {
        if (modules.contains(module)) {
            logger.warn("이미 등록된 모듈: {}", module.getClass().getName());
            return;
        }
        modules.add(module);
        moduleStates.put(module, ModuleStateEnum.CREATED);
        logger.info("모듈 [{}] 등록 및 상태 [{}]", module.getClass().getSimpleName(), ModuleStateEnum.CREATED);
    }

    /**
     * 모듈 제거 (실행 중이면 중지 후 제거)
     */
    public void removeModule(ModuleInterface module) {
        if (!modules.contains(module)) {
            logger.warn("미등록 모듈: {}", module.getClass().getName());
            return;
        }
        String moduleName = getModuleName(module);
        terminateModule(module);
        modules.remove(module);
        moduleStates.remove(module);
        commonExecutor.removeMainExecutor(moduleName); // 모듈 이름으로 메인 Executor 제거
        logger.info("[{}]의 메인 Executor 제거 완료", module.getClass().getSimpleName());
    }

    /**
     * 모든 모듈 실행
     */
    public void startAll() {
        for (ModuleInterface module : modules) {
            startModule(module);
        }
    }

    /**
     * 단일 모듈 실행
     */
    public void startModule(ModuleInterface module) {
        ModuleStateEnum currentState = moduleStates.get(module);
        if (currentState == null) {
            logger.warn("미등록 모듈(또는 이미 제거됨): {}", module.getClass().getName());
            return;
        }
        if (currentState == ModuleStateEnum.RUNNING) {
            logger.warn("[{}] 이미 RUNNING 상태", module.getClass().getSimpleName());
            return;
        }
        if (currentState == ModuleStateEnum.FAILED || currentState == ModuleStateEnum.TERMINATED) {
            logger.warn("[{}] 상태({})인 모듈에 대한 재시작은 별도 로직이 필요합니다.",
                    module.getClass().getSimpleName(), currentState);
        }

        String moduleName = getModuleName(module);
        ExecutorService mainExecutor = commonExecutor.addMainExecutor(moduleName);
        HashMap<String, Object> initialMap = prepareInitialMap(module);

        Future<?> future = mainExecutor.submit(() -> {
            try {
                // 1) initialize
                module.initialize(initialMap);
                moduleStates.put(module, ModuleStateEnum.INITIALIZED);
                logger.info("[{}] 모듈 INITIALIZED", module.getClass().getSimpleName());

                // 2) run
                module.run();
                moduleStates.put(module, ModuleStateEnum.RUNNING);
                logger.info("[{}] 모듈 RUNNING", module.getClass().getSimpleName());

                // run()이 종료되면 terminate()로 넘어온 것
                module.terminate();
                moduleStates.put(module, ModuleStateEnum.TERMINATED);
                logger.info("[{}] 모듈 TERMINATED", module.getClass().getSimpleName());

            } catch (InterruptedException ie) {
                logger.warn("[{}] 모듈 실행 중 인터럽트 발생", module.getClass().getSimpleName());
                Thread.currentThread().interrupt();
                safeTerminate(module, ModuleStateEnum.STOPPED);
            } catch (Exception e) {
                logger.error("[{}] 모듈 실행 중 예외 발생: {}", module.getClass().getSimpleName(), e.getMessage(), e);
                moduleStates.put(module, ModuleStateEnum.FAILED);
                safeTerminate(module, ModuleStateEnum.FAILED);
            }
        });

        moduleFutures.put(module, future);
        logger.info("[{}] 모듈 실행 요청 (이전 상태: {})", module.getClass().getSimpleName(), currentState);
    }

    /**
     * 모든 모듈 중지
     */
    public void stopAll() {
        for (ModuleInterface module : new ArrayList<>(modules)) {
            stopModule(module);
        }
    }

    /**
     * 모든 모듈 종료
     */
    public void terminateAll() {
        for (ModuleInterface module : new ArrayList<>(modules)) {
            terminateModule(module);
        }
    }

    /**
     * 단일 모듈 중지 (cancel(true)로 인터럽트)
     */
    public void stopModule(ModuleInterface module) {
        if (module.getStatus() == ModuleStateEnum.RUNNING) {
            module.stop();
            moduleStates.put(module, module.getStatus());
            logger.info("[{}] 모듈 stop 요청 완료. 상태=STOPPED",
                    module.getClass().getSimpleName());
        }
    }

    /**
     * 단일 모듈 종료 (terminate 호출 및 Future 취소)
     */
    public void terminateModule(ModuleInterface module) {
        module.terminate();
        Future<?> future = moduleFutures.remove(module);
        if (future != null) {
            boolean cancelled = future.cancel(true);
            if (cancelled) {
                logger.info("[{}] 모듈 중지 요청 (인터럽트)", module.getClass().getSimpleName());
            } else {
                logger.warn("[{}] 모듈 중지 실패 (이미 완료되었거나 취소됨)",
                        module.getClass().getSimpleName());
            }
        } else {
            logger.warn("[{}] 모듈 Future가 없어 중지 불가 (이미 중지되었거나 실행 안됨)",
                    module.getClass().getSimpleName());
        }
    }

    /**
     * 안전 종료를 위해 terminate()
     */
    private void safeTerminate(ModuleInterface module, ModuleStateEnum finalState) {
        try {
            module.terminate();
        } catch (Exception ex) {
            logger.error("[{}] terminate() 중 오류: {}", module.getClass().getSimpleName(), ex.getMessage(), ex);
        } finally {
            moduleStates.put(module, finalState);
            logger.info("[{}] 모듈 상태: {}", module.getClass().getSimpleName(), finalState);
        }
    }

    /**
     * 모듈 초기화 시 전달할 맵 구성
     */
    private HashMap<String, Object> prepareInitialMap(ModuleInterface module) {
        HashMap<String, Object> initMap = new HashMap<>();
        ModuleMetadata metadata = module.getClass().getAnnotation(ModuleMetadata.class);
        if (metadata != null) {
            initMap.put("config", configLoader.getEngineConfig(metadata.type(), metadata.name()));

            if (Arrays.stream(metadata.request()).anyMatch(r -> r == RequestEnum.SMTP)) {
                initMap.put("receivers", configLoader.getConfigs().getCore().getSmtp().getReceivers());
                initMap.put("sender",    configLoader.getConfigs().getCore().getSmtp().getSender());
            }
        }
        return initMap;
    }

    /**
     * JSON 형태로 전달된 정보를 통해 기존 Config를 부분 업데이트하고,
     * 실행 중인 모듈들의 설정을 다시 적용.
     *
     * @param jsonPayload 업데이트할 JSON 문자열
     * @throws Exception config 파싱/적용 실패 시 발생
     */
    public synchronized void updateConfigFromJson(String jsonPayload) throws Exception {
        logger.info("Updating config from JSON: {}", jsonPayload);
        configLoader.updateFromJson(jsonPayload);
        for (ModuleInterface module : modules) {
            ModuleStateEnum state = moduleStates.get(module);
            if (state == ModuleStateEnum.RUNNING) {
                logger.info("Stopping and restarting module [{}] to apply new JSON-based config...", module.getClass().getSimpleName());
                stopModule(module);
                startModule(module);
            }
        }
        logger.info("Update config process from JSON finished.");
    }

    /**
     * JSON 형태로 전달된 정보를 통해 기존 Config를 부분 업데이트하고,
     * 특정 엔진의 실행 중인 모듈들의 설정을 다시 적용.
     *
     * @param engine     업데이트할 엔진
     * @param jsonPayload 업데이트할 JSON 문자열
     * @throws Exception config 파싱/적용 실패 시 발생
     */
    public synchronized void updateConfigFromJson(Engine engine, String jsonPayload) throws Exception {
        logger.info("Updating config from JSON: {}", jsonPayload);
        configLoader.updateFromJson(jsonPayload);

        for (ModuleInterface module : modules) {
            if (engine == module.getModuleEngine() && module.getStatus() == ModuleStateEnum.RUNNING) {
                stopModule(module);
                startModule(module);
            }
        }

        logger.info("Update config process from JSON finished.");
    }

    /**
     * 매니저 종료 시 모든 모듈 중지 -> CommonExecutor 종료
     */
    public void shutdown() {
        logger.info("ModuleManager shutdown 호출. 모든 모듈 중지 중...");
        terminateAll();
        commonExecutor.shutdownExecutors();
        logger.info("ModuleManager shutdown 완료");
    }

    @Override
    public ModuleStateEnum getModuleState(ModuleInterface module) {
        return moduleStates.get(module);
    }

    @Override
    public List<ModuleInterface> getAllModules() {
        return Collections.unmodifiableList(modules);
    }

    /**
     * 모듈의 고유 이름을 가져오는 메서드
     *
     * @param module 모듈
     * @return 모듈의 고유 이름
     */
    private String getModuleName(ModuleInterface module) {
        ModuleMetadata metadata = module.getClass().getAnnotation(ModuleMetadata.class);
        if (metadata != null) {
            return metadata.name().name().toLowerCase();
        } else {
            throw new IllegalArgumentException("모듈에 @ModuleMetadata가 누락되었습니다: " + module.getClass().getName());
        }
    }
}
