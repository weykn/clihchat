package clih.chat.client;

import clih.chat.ClihChat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;

import java.net.URI;

public class ClihChatClient implements ClientModInitializer {
    public static ClihChatClient INSTANCE;

    // Change this to point at your relay host.
    static final String RELAY_URL = "wss://clihchat.onrender.com";

    volatile RelayClient relay;

    // Only accessed on the main client thread — no synchronization needed.
    boolean suppressInterception = false;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> connectRelay());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            RelayClient r = relay;
            if (r != null) r.close();
        });

        ClientSendMessageEvents.ALLOW_COMMAND.register(this::handleCommand);

        // Send hello whenever the player joins a server so the relay knows about
        // this client before they've sent their first whisper.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            RelayClient r = relay;
            if (r != null && r.isOpen() && client.player != null) {
                r.sendHello(client.player.getName().getString());
            }
        });
    }

    void connectRelay() {
        try {
            RelayClient r = new RelayClient(URI.create(RELAY_URL));
            relay = r;
            r.connect(); // non-blocking
            ClihChat.LOGGER.info("[clih-chat] connecting to relay at {}", RELAY_URL);
        } catch (Exception e) {
            ClihChat.LOGGER.error("[clih-chat] failed to start relay connection: {}", e.toString());
            relay = null;
        }
    }

    // Called by RelayClient after a disconnection so we can reconnect.
    void scheduleReconnect() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000);
                ClihChat.LOGGER.info("[clih-chat] attempting reconnect…");
                connectRelay();
            } catch (InterruptedException ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    private boolean handleCommand(String command) {
        if (suppressInterception) return true;

        RelayClient r = relay;
        if (r == null) {
            ClihChat.LOGGER.debug("[clih-chat] relay is null, passing '{}' to vanilla", command);
            return true;
        }
        if (!r.isOpen()) {
            ClihChat.LOGGER.debug("[clih-chat] relay not open (state={}), passing '{}' to vanilla",
                    r.getReadyState(), command);
            return true;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return true;

        String me = mc.player.getName().getString();

        int sp = command.indexOf(' ');
        String cmd  = sp >= 0 ? command.substring(0, sp).toLowerCase() : command.toLowerCase();
        String rest = sp >= 0 ? command.substring(sp + 1) : "";

        return switch (cmd) {
            case "msg", "tell", "w" -> {
                int sp2 = rest.indexOf(' ');
                if (sp2 < 0) yield true; // no message body — let vanilla handle it
                String target = rest.substring(0, sp2);
                String msg    = rest.substring(sp2 + 1);
                ClihChat.LOGGER.info("[clih-chat] routing whisper from {} to {}", me, target);
                r.sendWhisper(me, target, msg);
                yield false; // cancel vanilla send
            }
            case "r" -> {
                String last = r.getLastSender();
                if (last == null || rest.isEmpty()) yield true;
                ClihChat.LOGGER.info("[clih-chat] routing reply from {} to {}", me, last);
                r.sendWhisper(me, last, rest);
                yield false;
            }
            default -> true;
        };
    }

    // Called on the main thread by RelayClient when the relay cannot deliver.
    void fallback(String to, String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        suppressInterception = true;
        mc.player.networkHandler.sendChatCommand("msg " + to + " " + msg);
        suppressInterception = false;
    }
}
