package com.blockscout.proxy;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Configuration configuration = Configuration.fromEnvironment();
        BesuClient client = new BesuClient(configuration.besuUrl(), configuration.besuTimeout());
        SqliteStore store = new SqliteStore(configuration.databasePath());
        TrackedContractStore trackedContracts = new TrackedContractStore(
                configuration.contractConfigPath(),
                configuration.addresses(),
                configuration.topic0Values(),
                configuration.startBlock());
        List<AutoCloseable> services = new ArrayList<>();
        EventIndexer indexer = null;

        if (configuration.indexerEnabled()) {
            indexer = new EventIndexer(client, store, configuration, trackedContracts);
            indexer.start();
            services.add(indexer);
            System.out.printf("Event indexer started with SQLite at %s%n", configuration.databasePath());
        }
        if (configuration.apiEnabled()) {
            NodeRegistry nodeRegistry =
                    NodeRegistry.fromEnvironment(configuration.besuUrl(), configuration.besuTimeout(), client);
            ProxyServer server = new ProxyServer(
                    new InetSocketAddress(configuration.httpHost(), configuration.httpPort()),
                    client,
                    store,
                    new InternalUiService(nodeRegistry, indexer, trackedContracts, store),
                    configuration.contractAdminToken());
            server.start(Executors.newVirtualThreadPerTaskExecutor());
            services.add(server::stop);
            System.out.printf(
                    "Besu Etherscan-compatible proxy listening on http://%s:%d%n",
                    configuration.httpHost(), configuration.httpPort());
        }
        System.out.printf("Besu upstream: %s%n", configuration.besuUrl());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> services.forEach(Main::closeQuietly)));
        Thread.currentThread().join();
    }

    private static void closeQuietly(AutoCloseable service) {
        try {
            service.close();
        } catch (Exception exception) {
            System.err.println("Failed to stop service: " + exception.getMessage());
        }
    }
}
