package com.vnc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AppRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AppRegistry.class);

    private final ObjectMapper objectMapper;
    private final Map<String, AppInstance> instances = new LinkedHashMap<>();
    private volatile boolean running;

    public AppRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        createApp("1", "Bouncing Balls");
        createApp("2", "Bouncing Balls 2");
        createApp("3", "Bouncing Balls 3");

        instances.values().forEach(AppInstance::start);
        running = true;
        log.info("AppRegistry started – {} apps", instances.size());
    }

    private void createApp(String id, String name) {
        instances.put(id, new AppInstance(id, name, objectMapper));
    }

    @Override
    public void stop() {
        instances.values().forEach(AppInstance::stop);
        running = false;
        log.info("AppRegistry stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 1;
    }

    public AppInstance get(String id) {
        return instances.get(id);
    }

    public List<AppInfo> listApps() {
        return instances.values().stream()
                .map(a -> new AppInfo(a.getId(), a.getName()))
                .toList();
    }

    public record AppInfo(String id, String name) {}
}
