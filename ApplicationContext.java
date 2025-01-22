package com.dsmentoring.serverkeeper.core;

import com.dsmentoring.serverkeeper.core.resolver.Dispatcher;
import com.dsmentoring.serverkeeper.core.resolver.DispatcherHandler;
import com.dsmentoring.serverkeeper.core.channels.ServerRunner;
import com.dsmentoring.serverkeeper.core.channels.network.HttpServerRunner;
import com.dsmentoring.serverkeeper.core.manager.ModuleManager;
import com.dsmentoring.serverkeeper.core.manager.ModuleManagerInterface;
import com.dsmentoring.serverkeeper.core.manager.components.ConfigLoader;

import java.util.ArrayList;
import java.util.List;

public class ApplicationContext {
    private final ConfigLoader configLoader;
    private final ModuleManagerInterface moduleManager;
    private final DispatcherHandler dispatcher;
    private final List<ServerRunner> serverRunners;

    public ApplicationContext(String configFilePath) throws Exception {
        // ConfigLoader 초기화
        this.configLoader = new ConfigLoader(configFilePath);

        // ModuleManager 초기화
        this.moduleManager = new ModuleManager(configLoader);

        // Dispatcher (DispatcherHandleror) 초기화
        this.dispatcher = new Dispatcher(moduleManager);

        // ServerRunners 초기화
        this.serverRunners = new ArrayList<>();
        this.serverRunners.add(new HttpServerRunner(configLoader.getConfigs().getCore().getHttp(), dispatcher));
    }

    public ModuleManagerInterface getModuleManager() {
        return moduleManager;
    }

    public List<ServerRunner> getServerRunners() {
        return serverRunners;
    }
}
