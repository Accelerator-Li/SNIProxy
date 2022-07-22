package cc.nium.sni;

import cc.nium.sni.config.Config;
import cc.nium.sni.config.ServerConfig;
import cc.nium.sni.io.SNIServerSocket;
import cc.nium.sni.io.SNISocket;
import cc.nium.sni.util.ConcurrentHashSet;
import cc.nium.sni.util.Json;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Main {

    private static ExecutorService pool = Executors.newCachedThreadPool();
    private static ConcurrentHashSet<SNISocket> allSockets = new ConcurrentHashSet<>();
    private static ArrayList<SNIServerSocket> serverSockets = new ArrayList<>();

    public static void main(String[] args) {
        try {
            final Config config = Json.instance().readValue(new File("SNIProxy.json"), Config.class);
            final int headBufferSize = config.getHeadBufferSize();
            if (headBufferSize <= 0 || headBufferSize > 1024 * 1024 * 1024) {
                throw new RuntimeException("headBufferSize " + headBufferSize + " out of range: (0, " + 1024 * 1024 * 1024 + "]");
            }
            final int forwarderBufferSize = config.getForwarderBufferSize();
            if (forwarderBufferSize <= 0 || forwarderBufferSize > 1024 * 1024 * 1024) {
                throw new RuntimeException("forwarderBufferSize " + forwarderBufferSize + " out of range: (0, " + 1024 * 1024 * 1024 + "]");
            }
            System.out.println("========================================");
            System.out.println("headBufferSize      = " + headBufferSize);
            System.out.println("forwarderBufferSize = " + forwarderBufferSize);
            System.out.println("========================================");
            final ArrayList<ServerConfig> serverConfigs = config.getServers();
            if (serverConfigs == null || serverConfigs.size() == 0) {
                System.err.println("empty servers");
                return;
            }
            for (final ServerConfig serverConfig : serverConfigs) {
                final SNIServerSocket serverSocket = new SNIServerSocket(pool, allSockets, config, serverConfig);
                serverSockets.add(serverSocket);
            }
            accept();
        } catch (InvalidFormatException e) {
            System.err.println("config error");
            final JsonLocation location = e.getLocation();
            System.err.println("\"" + e.getValue() + "\" is not " + e.getTargetType().getSimpleName() + " [line: " + location.getLineNr() + ", column: " + location.getColumnNr() + "]");
        } catch (IOException e) {
            System.err.println("config error");
            e.printStackTrace();
        }
    }

    @SuppressWarnings({"InfiniteLoopStatement", "EmptyCatchBlock"})
    private static void accept() {
        while (true) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
            for (final SNIServerSocket serverSocket : serverSockets) {
                try {
                    serverSocket.accept();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
