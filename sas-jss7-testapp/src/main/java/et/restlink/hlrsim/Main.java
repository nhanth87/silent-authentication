/*
 * Simulated home HLR for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.hlrsim;

import java.util.concurrent.CountDownLatch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import et.restlink.hlrsim.web.ControlWebServer;

/**
 * Entry point: jSS7 server stack (simulated home HLR) + control web UI.
 *
 * <p>Defaults match the SAS sample config {@code ss7-sas.json}: the simulated
 * HLR listens on SCTP {@code 127.0.0.1:2906} expecting the SAS ASP to dial in
 * from {@code 127.0.0.1:2905}, and the control UI serves {@code :8087}.</p>
 */
public final class Main {

    private static final Logger LOG = LogManager.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int listenPort = 2906;
        int peerPort = 2905;
        int httpPort = 8087;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = requireValue(args, ++i, "--host");
                case "--listen-port" -> listenPort = Integer.parseInt(requireValue(args, ++i, "--listen-port"));
                case "--peer-port" -> peerPort = Integer.parseInt(requireValue(args, ++i, "--peer-port"));
                case "--http-port" -> httpPort = Integer.parseInt(requireValue(args, ++i, "--http-port"));
                case "--help", "-h" -> {
                    System.out.println("usage: hlrsim [--host 127.0.0.1] [--listen-port 2906]"
                            + " [--peer-port 2905] [--http-port 8087]");
                    return;
                }
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }

        HlrSimulator hlr = new HlrSimulator(host, listenPort, peerPort);
        hlr.start();

        ControlWebServer web = new ControlWebServer(hlr);
        web.start(host, httpPort);

        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.stop();
            hlr.stop();
            done.countDown();
        }, "hlrsim-shutdown"));

        LOG.info("[hlr-sim] ready — SCTP {}:{}", host, listenPort);
        done.await();
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[index];
    }
}
