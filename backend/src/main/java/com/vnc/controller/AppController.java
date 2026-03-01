package com.vnc.controller;

import com.vnc.service.AppRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AppController {

    private final AppRegistry appRegistry;

    public AppController(AppRegistry appRegistry) {
        this.appRegistry = appRegistry;
    }

    @GetMapping("/apps")
    public List<AppRegistry.AppInfo> listApps() {
        return appRegistry.listApps();
    }
}
