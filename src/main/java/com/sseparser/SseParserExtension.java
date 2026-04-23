package com.sseparser;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.sseparser.ui.SseTab;

/** Burp extension entry point, wires the store, relay, UI and proxy hook together. */
public class SseParserExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("SSE Parser");

        SseEventStore     store          = new SseEventStore();
        SseInterceptQueue interceptQueue = new SseInterceptQueue();
        SseRelayServer    relay          = new SseRelayServer(store, api, interceptQueue);

        int relayPort;
        try {
            relayPort = relay.start();
        } catch (Exception e) {
            api.logging().logToError("SSE Parser: relay failed to start, " + e.getMessage());
            return;
        }

        SseTab tab = new SseTab(store, interceptQueue, relay, api);
        api.userInterface().registerSuiteTab("SSE Parser", tab.getComponent());

        api.proxy().registerRequestHandler(
            new SseProxyRequestHandler(api, relay, relayPort));

        // Free the relay port cleanly when the extension is unloaded or reloaded.
        api.extension().registerUnloadingHandler(() -> {
            relay.stop();
            api.logging().logToOutput("SSE Parser unloaded.");
        });

        api.logging().logToOutput(
            "SSE Parser loaded. Relay on localhost:" + relayPort +
            ", browse any SSE endpoint to start capturing.");
    }
}
