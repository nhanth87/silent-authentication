/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import et.restlink.testapp.diameter.HssDiameterServer;
import et.restlink.testapp.web.ControlWebServer;

/**
 * Entrypoint: starts the Diameter HSS/AAA server stack and the control web
 * UI. Args override ports:
 *
 * <pre>
 * java -jar sas-diameter-testapp.jar [--diameter-port 3868] [--web-port 8086]
 *                                    [--bind 127.0.0.1] [--tcp]
 * </pre>
 */
public final class Main {

    private static final Logger LOG = LogManager.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        int diameterPort = 3868;
        int webPort = 8086;
        String bind = "127.0.0.1";
        boolean sctp = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--diameter-port" -> diameterPort = Integer.parseInt(args[++i]);
                case "--web-port" -> webPort = Integer.parseInt(args[++i]);
                case "--bind" -> bind = args[++i];
                case "--tcp" -> sctp = false;
                default -> throw new IllegalArgumentException("unknown arg " + args[i]
                        + " (expected --diameter-port, --web-port, --bind, --tcp)");
            }
        }

        MessageLog messageLog = new MessageLog();
        HssSimulator hss = new HssSimulator(messageLog);
        HssDiameterServer diameter = new HssDiameterServer(hss, bind, diameterPort, sctp);
        ControlWebServer web = new ControlWebServer(hss, diameter);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutting down");
            web.stop();
            diameter.stop();
        }, "shutdown"));

        diameter.start();
        web.start(bind, webPort);
        LOG.info("HSS simulator ready — demo subscriber IMSI={} MSISDN={}",
                HssSimulator.DEMO_IMSI, HssSimulator.DEMO_MSISDN);
    }

    private Main() {
    }
}
