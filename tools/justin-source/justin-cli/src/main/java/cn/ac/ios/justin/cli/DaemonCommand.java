package cn.ac.ios.justin.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Callable;

/**
 * Runs Justin as a long-lived process driven over JSON-RPC 2.0 (see {@link DaemonServer}): on
 * stdin/stdout by default, or on a local TCP port serving one connection at a time. Logging goes to
 * stderr, so stdout carries the protocol only.
 */
@Command(name = "daemon", mixinStandardHelpOptions = true, description = "Serve generate/status/result/cancel requests over JSON-RPC (stdin/stdout, or --port) for IDE plugins and other tools.")
public final class DaemonCommand implements Callable<Integer> {

    @Option(names = "--port", defaultValue = "0", description = "Listen on this TCP port (loopback only) instead of stdin/stdout")
    int port;

    @Override public Integer call() throws Exception {
        if (port <= 0) {
            new DaemonServer(System.in, System.out).run();
            return 0;
        }
        try (ServerSocket server = new ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())) {
            System.err.println("[justin] daemon listening on 127.0.0.1:" + server.getLocalPort());
            while (true) {
                try (Socket client = server.accept()) {
                    System.err.println("[justin] client connected from " + client.getRemoteSocketAddress());
                    new DaemonServer(client.getInputStream(), client.getOutputStream()).run();
                    System.err.println("[justin] client disconnected");
                }
            }
        }
    }
}
