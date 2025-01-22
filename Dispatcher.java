package com.dsmentoring.serverkeeper.core.channels;

import com.dsmentoring.serverkeeper.common.data.ModuleMetadata;
import com.dsmentoring.serverkeeper.core.channels.network.data.RequestDTO;
import com.dsmentoring.serverkeeper.core.channels.network.data.ResponseDTO;
import com.dsmentoring.serverkeeper.core.framework.ModuleInterface;
import com.dsmentoring.serverkeeper.core.manager.ModuleManagerInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

/**
 * 수신된 RequestDTO 를 보고 ModuleManager에 명령을 내리는 예시 로직
 */
public class Dispatcher implements DispatcherHandler {
    private static final Logger logger = LoggerFactory.getLogger(Dispatcher.class);

    private final ModuleManagerInterface moduleManager;

    public Dispatcher(ModuleManagerInterface moduleManager) {
        this.moduleManager = moduleManager;
    }

    @Override
    public ResponseDTO processRequest(RequestDTO req) {
        String requestId = extractRequestId(req);
        try {
            String requestType = req.getRequestType();
            String operation = req.getOperation();
            String moduleGroup = req.getModuleGroup();
            String moduleName = req.getModule();

            logger.info("[DispatcherHandleror] requestType={}, operation={}, moduleGroup={}, module={}", requestType, operation, moduleGroup, moduleName);

            switch (requestType) {
                case "command":
                    return handleCommandRequest(req, requestId);

                case "configure":
                    return handleConfigureRequest(req, requestId);

                default:
                    return new ResponseDTO("error", "Unknown requestType: " + requestType, requestId);
            }
        } catch (Exception e) {
            logger.error("Request 처리 중 예외 발생: {}", e.getMessage(), e);
            return new ResponseDTO("error", "Internal Server Error", requestId);
        }
    }

    private ResponseDTO handleCommandRequest(RequestDTO req, String requestId) {
        String operation = req.getOperation();
        String moduleName = req.getModule();

        ModuleInterface module = findModuleByName(moduleName);
        if (module == null) {
            return new ResponseDTO("error", "Module not found: " + moduleName, requestId);
        }

        if ("start".equalsIgnoreCase(operation)) {
            logger.info("[Command] {} 모듈 실행 요청", moduleName);
            moduleManager.startModule(module);
            return new ResponseDTO("success", moduleName + " 모듈이 시작되었습니다.", requestId);
        } else if ("stop".equalsIgnoreCase(operation)) {
            logger.info("[Command] {} 모듈 중지 요청", moduleName);
            moduleManager.stopModule(module);
            return new ResponseDTO("success", moduleName + " 모듈이 중지되었습니다.", requestId);
        } else {
            return new ResponseDTO("error", "알 수 없는 operation: " + operation, requestId);
        }
    }

    private ResponseDTO handleConfigureRequest(RequestDTO req, String requestId) {
        String operation = req.getOperation();
        String moduleName = req.getModule();

        if ("modify".equalsIgnoreCase(operation)) {
            logger.info("[Configure] {} 모듈 설정 수정 요청. payload={}", moduleName, req.getPayload());
            // 설정 수정 로직을 여기에 추가해야 합니다.
            return new ResponseDTO("success", moduleName + " 설정이 수정되었습니다.", requestId);
        } else {
            return new ResponseDTO("error", "알 수 없는 operation: " + operation, requestId);
        }
    }

    private String extractRequestId(RequestDTO req) {
        if (req.getMetadata() != null) {
            Object reqIdObj = req.getMetadata().getRequestId();
            return reqIdObj != null ? reqIdObj.toString() : null;
        }
        return null;
    }

    /**
     * 모듈 이름으로 모듈 객체를 찾는 유틸리티 메서드
     */
    private ModuleInterface findModuleByName(String moduleName) {
        for (ModuleInterface module : moduleManager.getAllModules()) {
            ModuleMetadata metadata = module.getClass().getAnnotation(ModuleMetadata.class);
            if (metadata != null && metadata.name().name().equalsIgnoreCase(moduleName)) {
                return module;
            }
        }
        return null;
    }
}
