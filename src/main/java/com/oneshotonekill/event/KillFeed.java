package com.oneshotonekill.event;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.match.ScoreboardManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Wer wen womit ausgeschaltet hat – als Zeile im Chat.
 * <p>
 * Bisher erfuhr das nur, wer selbst beteiligt war: der Täter über einen Ton, das Opfer über den
 * Respawn. Wer daneben stand, sah gar nichts. Der Feed macht aus Einzelereignissen ein
 * mitlesbares Spielgeschehen und beantwortet die Frage, die im Gefecht am häufigsten aufkommt –
 * womit war das jetzt?
 * <p>
 * Die Todesursache wird ausdrücklich durchgereicht, statt sie aus der {@code DamageSource}
 * zurückzurechnen: die Spezial-Items töten über die eigene Buchführung, wo Vanilla gar keine
 * Schadensquelle mehr sieht.
 */
public final class KillFeed {
   private static final ChatFormatting BRACKET = ChatFormatting.DARK_GRAY;
   private static final ChatFormatting BRAND = ChatFormatting.GOLD;
   private static final ChatFormatting TEXT = ChatFormatting.GRAY;
   private static final ChatFormatting KILLER = ChatFormatting.GREEN;
   private static final ChatFormatting VICTIM = ChatFormatting.RED;
   private static final ChatFormatting WEAPON = ChatFormatting.AQUA;
   private static final ChatFormatting SHIELD = ChatFormatting.BLUE;

   /** Ab dieser Serie wird sie mitgeschrieben – darunter ist sie keine Meldung wert. */
   private static final int STREAK_WORTH_MENTIONING = 2;

   /** Womit jemand ausgeschaltet wurde. */
   public enum Cause {
      BOW("item.minecraft.bow"),
      SWORD("item.minecraft.iron_sword"),
      MINIGUN("item.oneshotonekill.minigun"),
      RAILGUN("item.oneshotonekill.railgun"),
      EXPLOSIVE_SHOT("item.oneshotonekill.explosive_shot"),
      CHAIN_LIGHTNING("item.oneshotonekill.chain_lightning"),
      C4("item.oneshotonekill.c4"),
      SENTRY_TURRET("item.oneshotonekill.sentry_turret"),
      AIRSTRIKE("item.oneshotonekill.airstrike"),
      STEALTH_BOMBER("item.oneshotonekill.stealth_bomber");

      private final String translationKey;

      Cause(String translationKey) {
         this.translationKey = translationKey;
      }

      public String getTranslationKey() {
         return translationKey;
      }

      public Component getComponent() {
         return Component.translatable(translationKey);
      }

      public String getLabel() {
         return getComponent().getString();
      }
   }

   private KillFeed() {
   }

   /** Ein Abschuss – oder, wenn Täter und Opfer dieselbe Person sind, ein Eigentor. */
   public static void kill(ServerPlayer killer, ServerPlayer victim, Cause cause) {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server == null) {
         return;
      }

      if (killer.equals(victim)) {
         broadcast(server, prefix("☠", VICTIM)
            .append(Component.translatable("chat.oneshotonekill.kill_self",
               name(victim, VICTIM),
               cause.getComponent().copy().withStyle(WEAPON)
            ).withStyle(TEXT)));
         return;
      }

      MutableComponent line = prefix("⚔", KILLER)
         .append(Component.translatable("chat.oneshotonekill.kill",
            name(killer, KILLER),
            name(victim, VICTIM),
            cause.getComponent().copy().withStyle(WEAPON)
         ).withStyle(TEXT));

      int streak = ScoreboardManager.INSTANCE.getStreak(killer.getUUID());
      if (streak >= STREAK_WORTH_MENTIONING) {
         line.append(Component.literal(" · ").withStyle(BRACKET))
            .append(Component.translatable("chat.oneshotonekill.streak_suffix", streak).withStyle(BRAND));
      }
      broadcast(server, line);
   }

   /**
    * Der Reflektor-Schild hat einen Treffer geschluckt.
    * <p>
    * Bisher erfuhr das nur der Getroffene über eine Actionbar-Zeile. Für den Angreifer sah es
    * aus, als hätte er schlicht verfehlt – und fuer alle anderen war der wichtigste Moment des
    * Duells unsichtbar. Ein abgewehrter Luftangriff ist eine Nachricht wert.
    */
   public static void blocked(ServerPlayer defender, ServerPlayer attacker, Cause cause) {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server == null) {
         return;
      }

      MutableComponent line = prefix("🛡", SHIELD);
      if (attacker == null || attacker.equals(defender)) {
         line.append(Component.translatable("chat.oneshotonekill.deflect_self",
            name(defender, SHIELD),
            cause.getComponent().copy().withStyle(WEAPON)
         ).withStyle(TEXT));
      } else {
         line.append(Component.translatable("chat.oneshotonekill.deflect",
            name(defender, SHIELD),
            cause.getComponent().copy().withStyle(WEAPON),
            name(attacker, VICTIM)
         ).withStyle(TEXT));
      }
      broadcast(server, line);
   }

   /** Eine Killserie hat ein Spezial-Item eingebracht. */
   public static void streakReward(ServerPlayer player, int streak, String itemName) {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server == null) {
         return;
      }
      Component itemComponent = (itemName != null && (itemName.startsWith("item.") || itemName.startsWith("equipment.")))
         ? Component.translatable(itemName)
         : Component.literal(itemName != null ? itemName : "");
      broadcast(server, prefix("⚡", BRAND)
         .append(Component.translatable("chat.oneshotonekill.streak_reward",
            name(player, KILLER),
            streak,
            itemComponent.copy().withStyle(WEAPON)
         ).withStyle(TEXT)));
   }

   /** Tod ohne Gegner: Sturz, Void, die eigene Ladung. */
   public static void death(ServerPlayer victim) {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server == null) {
         return;
      }
      broadcast(server, prefix("☠", VICTIM)
         .append(Component.translatable("chat.oneshotonekill.death",
            name(victim, VICTIM)
         ).withStyle(TEXT)));
   }

   private static MutableComponent prefix(String symbol, ChatFormatting symbolColor) {
      return Component.empty()
         .append(Component.literal("[").withStyle(BRACKET))
         .append(Component.literal("OSOK").withStyle(BRAND, ChatFormatting.BOLD))
         .append(Component.literal("] ").withStyle(BRACKET))
         .append(Component.literal(symbol + " ").withStyle(symbolColor, ChatFormatting.BOLD));
   }

   private static Component name(ServerPlayer player, ChatFormatting colour) {
      return Component.literal(player.getGameProfile().name()).withStyle(colour, ChatFormatting.BOLD);
   }

   private static Component text(String literal) {
      return Component.literal(literal).withStyle(TEXT);
   }

   private static void broadcast(MinecraftServer server, Component message) {
      server.getPlayerList().broadcastSystemMessage(message, false);
   }
}
