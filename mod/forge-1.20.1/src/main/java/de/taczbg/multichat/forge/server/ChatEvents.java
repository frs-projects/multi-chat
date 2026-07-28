package de.taczbg.multichat.forge.server;

import de.taczbg.multichat.forge.MultiChat;
import de.taczbg.multichat.forge.config.MultiChatConfig;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Taps the built-in event types onto the stream. Pure observers: nothing is cancelled or
 * modified. All of these fire on the server thread; publish() only enqueues, so they add
 * no I/O to the tick.
 */
@Mod.EventBusSubscriber(modid = MultiChat.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChatEvents {

    private ChatEvents() {
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        if (!ready() || !MultiChatConfig.forwardChat()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        publish("chat", player, event.getMessage().getString());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (ready() && MultiChatConfig.forwardJoinLeave() && event.getEntity() instanceof ServerPlayer player) {
            publish("join", player, "");
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (ready() && MultiChatConfig.forwardJoinLeave() && event.getEntity() instanceof ServerPlayer player) {
            publish("leave", player, "");
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!ready() || !MultiChatConfig.forwardDeaths()
                || !(event.getEntity() instanceof ServerPlayer player)
                || !player.level().getGameRules().getBoolean(GameRules.RULE_SHOWDEATHMESSAGES)) {
            return;
        }
        // Same component vanilla is about to broadcast; rendered server-side (en_us).
        publish("death", player, player.getCombatTracker().getDeathMessage().getString());
    }

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!ready() || !MultiChatConfig.forwardAdvancements()
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Advancement advancement = event.getAdvancement();
        DisplayInfo display = advancement.getDisplay();
        if (display == null || !display.shouldAnnounceChat()
                || !player.level().getGameRules().getBoolean(GameRules.RULE_ANNOUNCE_ADVANCEMENTS)) {
            return;
        }
        // Mirrors PlayerAdvancements' chat broadcast, rendered server-side (en_us).
        String text = Component.translatable("chat.type.advancement." + display.getFrame().getName(),
                player.getDisplayName(), advancement.getChatComponent()).getString();
        publish("advancement", player, text);
    }

    private static boolean ready() {
        return MultiChat.CORE.isRunning();
    }

    private static void publish(String type, ServerPlayer player, String content) {
        MultiChat.CORE.publish(type, player.getUUID().toString(),
                player.getGameProfile().getName(), content, "");
    }
}
