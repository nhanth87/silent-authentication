/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.diameter;

import et.restlink.sas.ras.s6averifier.CorsacS6aVerifierBackend;
import et.restlink.sas.ras.swxverifier.CorsacSwxVerifierBackend;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thin admin facade above {@link DiameterConfigService}: validate/save/reload
 * the multi-realm JSON and push the new config into any live S6a/SWx backends.
 */
@ApplicationScoped
public class DiameterAdminSupport {

    private static final Logger LOG = LogManager.getLogger(DiameterAdminSupport.class);

    @Inject
    DiameterConfigService configService;

    @Inject
    Instance<CorsacS6aVerifierBackend> s6aBackends;

    @Inject
    Instance<CorsacSwxVerifierBackend> swxBackends;

    private final List<Consumer<DiameterConfig>> extraBackendHooks = new CopyOnWriteArrayList<>();

    @PostConstruct
    void init() {
        configService.register(() -> reconfigureBackends());
        LOG.info("Diameter admin support wired (auto-reconfigure on diameter.json reload)");
    }

    public String activeJson() {
        return configService.activeJson();
    }

    public DiameterConfig current() {
        return configService.current();
    }

    public DiameterConfigService.Result validate(String json) {
        return configService.validate(json);
    }

    public String save(String json) {
        return configService.save(json);
    }

    public DiameterConfig reload() {
        return configService.reload();
    }

    public DiameterConfigService.StatusRecord status() {
        return configService.status();
    }

    /** Register a custom backend reconfiguration callback (non-CDI wiring). */
    public void registerBackend(Consumer<DiameterConfig> reconfigure) {
        if (reconfigure != null) {
            extraBackendHooks.add(reconfigure);
        }
    }

    /** Reconfigure all known S6a/SWx Diameter backends with the current config. */
    public boolean reconfigureBackends() {
        DiameterConfig cfg = configService.current();
        boolean ok = true;

        for (Consumer<DiameterConfig> hook : extraBackendHooks) {
            try {
                hook.accept(cfg);
            } catch (Exception e) {
                LOG.warn("custom diameter backend reconfigure failed", e);
                ok = false;
            }
        }

        if (s6aBackends.isResolvable()) {
            try {
                s6aBackends.get().reconfigure(cfg);
            } catch (Exception e) {
                LOG.warn("S6a backend reconfigure failed", e);
                ok = false;
            }
        }

        if (swxBackends.isResolvable()) {
            try {
                swxBackends.get().reconfigure(cfg);
            } catch (Exception e) {
                LOG.warn("SWx backend reconfigure failed", e);
                ok = false;
            }
        }

        return ok;
    }

    public record AdminSnapshot(String activeJson, DiameterConfig config, List<String> backendStates) {}

    public AdminSnapshot snapshot() {
        DiameterConfig cfg = configService.current();
        return new AdminSnapshot(configService.activeJson(), cfg, backendStates());
    }

    private List<String> backendStates() {
        List<String> states = new ArrayList<>();
        states.add("s6a=" + (s6aBackends.isResolvable() ? "linked" : "not-wired"));
        states.add("swx=" + (swxBackends.isResolvable() ? "linked" : "not-wired"));
        if (!extraBackendHooks.isEmpty()) {
            states.add("custom=" + extraBackendHooks.size());
        }
        return states;
    }
}