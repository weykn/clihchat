package clih.chat.client;

import clih.chat.ClihChat;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

public class RelayClient extends WebSocketClient {
    private static final Gson GSON = new Gson();
    private static final int COLOR_FROM  = 0x5EEAD4; // seafoam  — sender name
    private static final int COLOR_ARROW = 0x64748B; // slate    — " -> " and ":"
    private static final int COLOR_TO    = 0xF472B6; // pink     — recipient name
    private static final int COLOR_MSG   = 0xE2E8F0; // off-white — message body

    // Written on WS thread, read on main thread — volatile is sufficient.
    private volatile String lastSender;

    public RelayClient(URI uri) {
        super(uri);
    }

    public String getLastSender() {
        return lastSender;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        ClihChat.LOGGER.info("[clih-chat] relay connected");
        // If the player is already in a game when the relay (re)connects, register now.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            sendHello(mc.player.getName().getString());
        }
        // Otherwise ClihChatClient.JOIN fires when the player enters a world.
    }

    @Override
    public void onMessage(String raw) {
        JsonObject obj;
        try {
            obj = GSON.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (obj.has("type")) {
            String type = obj.get("type").getAsString();
            if ("sent".equals(type) && obj.has("to") && obj.has("msg")) {
                String to  = obj.get("to").getAsString();
                String msg = obj.get("msg").getAsString();
                mc.execute(() -> {
                    String me = mc.player != null ? mc.player.getName().getString() : "?";
                    showMessage(me, to, msg);
                });
            } else if ("fallback".equals(type) && obj.has("to") && obj.has("msg")) {
                String to  = obj.get("to").getAsString();
                String msg = obj.get("msg").getAsString();
                ClihChat.LOGGER.info("[clih-chat] fallback for {} -> {}", to, msg);
                mc.execute(() -> ClihChatClient.INSTANCE.fallback(to, msg));
            }
        } else if (obj.has("from") && obj.has("to") && obj.has("msg")) {
            String from = obj.get("from").getAsString();
            String to   = obj.get("to").getAsString();
            String msg  = obj.get("msg").getAsString();
            lastSender  = from;
            mc.execute(() -> showMessage(from, to, msg));
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        ClihChat.LOGGER.warn("[clih-chat] relay disconnected (code={} reason={}), will retry in 5s", code, reason);
        ClihChatClient.INSTANCE.scheduleReconnect();
    }

    @Override
    public void onError(Exception ex) {
        ClihChat.LOGGER.error("[clih-chat] relay error: {}", ex.toString());
    }

    public void sendHello(String name) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "hello");
        obj.addProperty("name", name);
        send(GSON.toJson(obj));
        ClihChat.LOGGER.info("[clih-chat] registered as {}", name);
    }

    public void sendWhisper(String from, String to, String msg) {
        JsonObject obj = new JsonObject();
        obj.addProperty("from", from);
        obj.addProperty("to", to);
        obj.addProperty("msg", msg);
        send(GSON.toJson(obj));
    }

    private static void showMessage(String from, String to, String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;
        Text line = Text.literal(from).setStyle(Style.EMPTY.withColor(COLOR_FROM))
                .append(Text.literal(" -> ").setStyle(Style.EMPTY.withColor(COLOR_ARROW)))
                .append(Text.literal(to).setStyle(Style.EMPTY.withColor(COLOR_TO)))
                .append(Text.literal(": ").setStyle(Style.EMPTY.withColor(COLOR_ARROW)))
                .append(Text.literal(msg).setStyle(Style.EMPTY.withColor(COLOR_MSG)));
        mc.inGameHud.getChatHud().addMessage(line);
    }
}
