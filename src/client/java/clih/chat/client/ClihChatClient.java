package clih.chat.client;

import clih.chat.ClihChat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;

public class ClihChatClient implements ClientModInitializer {
    public static ClihChatClient INSTANCE;

    static final String RELAY_URL = "wss://clihchat.onrender.com";

    volatile RelayClient relay;

    // Only accessed on the main client thread — no synchronization needed.
    boolean suppressInterception = false;

    // Whispers queued while relay is down. Flushed after hello on reconnect.
    final Deque<String[]> pendingWhispers = new ArrayDeque<>();

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> connectRelay());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            RelayClient r = relay;
            if (r != null) r.close();
        });

        ClientSendMessageEvents.ALLOW_COMMAND.register(this::handleCommand);

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
            r.connect();
            ClihChat.LOGGER.info("[clih-chat] connecting to relay at {}", RELAY_URL);
        } catch (Exception e) {
            ClihChat.LOGGER.error("[clih-chat] failed to start relay connection: {}", e.toString());
            relay = null;
            scheduleReconnect();
        }
    }

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

    // Called on main thread by RelayClient after hello is sent and relay is ready.
    void flushPending(String myName) {
        if (pendingWhispers.isEmpty()) return;
        RelayClient r = relay;
        if (r == null || !r.isOpen()) return;
        int count = pendingWhispers.size();
        while (!pendingWhispers.isEmpty()) {
            String[] w = pendingWhispers.poll();
            r.sendWhisper(myName, w[0], w[1]);
        }
        showStatus("relay reconnected — sent " + count + " queued message(s)", 0x4ADE80);
    }

    private boolean handleCommand(String command) {
        if (suppressInterception) return true;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return true;

        String me = mc.player.getName().getString();

        int sp = command.indexOf(' ');
        String cmd  = sp >= 0 ? command.substring(0, sp).toLowerCase() : command.toLowerCase();
        String rest = sp >= 0 ? command.substring(sp + 1) : "";

        return switch (cmd) {
            case "msg", "tell", "w" -> {
                int sp2 = rest.indexOf(' ');
                if (sp2 < 0) yield true;
                String target = rest.substring(0, sp2);
                String msg    = rest.substring(sp2 + 1);
                RelayClient r = relay;
                if (r != null && r.isOpen()) {
                    ClihChat.LOGGER.info("[clih-chat] routing whisper from {} to {}", me, target);
                    r.sendWhisper(me, target, msg);
                } else {
                    // Never fall through to vanilla — queue and wait for relay
                    pendingWhispers.add(new String[]{target, msg});
                    showStatus("relay reconnecting… whisper to " + target + " queued", 0xFBBF24);
                }
                yield false;
            }
            case "r" -> {
                RelayClient r = relay;
                String last = r != null ? r.getLastSender() : null;
                if (last == null || rest.isEmpty()) yield true;
                if (r.isOpen()) {
                    ClihChat.LOGGER.info("[clih-chat] routing reply from {} to {}", me, last);
                    r.sendWhisper(me, last, rest);
                } else {
                    pendingWhispers.add(new String[]{last, rest});
                    showStatus("relay reconnecting… reply to " + last + " queued", 0xFBBF24);
                }
                yield false;
            }
            default -> true;
        };
    }

    // Only called when relay confirms recipient is not on the relay at all.
    void fallback(String to, String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        suppressInterception = true;
        mc.player.connection.sendCommand("msg " + to + " " + msg);
        suppressInterception = false;
    }

    static void showStatus(String text, int rgb) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.sendSystemMessage(
            Component.literal("[clih-chat] " + text)
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)))
        );
    }
}
