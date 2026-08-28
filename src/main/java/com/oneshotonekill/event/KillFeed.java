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
 *
 * Bisher erfuhr das nur, wer selbst beteiligt war: der Täter über einen Ton, das Opfer über den
 * Respawn. Wer daneben stand, sah gar nichts. Der Feed macht aus Einzelereignissen ein
 * mitlesbares Spielgeschehen und beantwortet die Frage, die im Gefecht am häufigsten aufkommt –
 * womit war das jetzt?
 *
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

   /** Womit jemand ausgeschaltet wurde. Der Name steht so im Chat. */
   public enum Cause {
      BOW("Bogen"),
      SWORD("Schwert"),
      MINIGUN("Minigun"),
      RAILGUN("Railgun"),
      EXPLOSIVE_SHOT("Explosiv-Schuss"),
      CHAIN_LIGHTNING("Kettenblitz"),
      C4("C4"),
      SENTRY_TURRET("Geschützturm"),
      AIRSTRIKE("Luftangriff"),
      STEALTH_BOMBER("Tarnkappenbomber");

      private final String label;

      Cause(String label) {
         this.label = label;
      }

      public String getLabel() {
         return label;
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
            .append(name(victim, VICTIM))
            .append(text(" hat sich selbst mit "))
            .append(Component.literal(cause.getLabel()).withStyle(WEAPON))
            .append(text(" ausgeschaltet")));
         return;
      }

      MutableComponent line = prefix("⚔", KILLER)
         .append(name(killer, KILLER))
         .append(text(" hat "))
         .append(name(victim, VICTIM))
         .append(text(" mit "))
         .append(Component.literal(cause.getLabel()).withStyle(WEAPON))
         .append(text(" ausgeschaltet"));

      int streak = ScoreboardManager.INSTANCE.getStreak(killer.getUUID());
      if (streak >= STREAK_WORTH_MENTIONING) {
         line.append(Component.literal(" · ").withStyle(BRACKET))
            .append(Component.literal("Serie " + streak).withStyle(BRAND));
      }
      broadcast(server, line);
   }

   /**
    * Der Reflektor-Schild hat einen Treffer geschluckt.
    *
    * Bisher erfuhr das nur der Getroffene über eine Actionbar-Zeile. Für den Angreifer sah es
    * aus, als hätte er schlicht verfehlt – und fuer alle anderen war der wichtigste Moment des
    * Duells unsichtbar. Ein abgewehrter Luftangriff ist eine Nachricht wert.
    */
   public static void blocked(ServerPlayer defender, ServerPlayer attacker, Cause cause) {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server == null) {
         return;
      }

      MutableComponent line = prefix("🛡", SHIELD)
         .append(name(defender, SHIELD))
         .append(text(" hat "));

      if (attacker == null || attacker.equals(defender)) {
         line.append(Component.literal(cause.getLabel()).withStyle(WEAPON));
      } else {
         line.append(Component.literal(cause.getLabel()).withStyle(WEAPON))
            .append(text(" von "))
            .append(name(attacker, VICTIM));
      }
      broadcast(server, line.append(text(" abgewehrt")));
   }

   /** Eine Killserie hat ein Spezial-Item eingebracht. */
   public static void streakReward(ServerPlayer player, int streak, String itemName) {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server == null) {
         return;
      }
      broadcast(server, prefix("⚡", BRAND)
         .append(name(player, KILLER))
         .append(text(" hält eine Serie von "))
         .append(Component.literal(Integer.toString(streak)).withStyle(BRAND, ChatFormatting.BOLD))
         .append(text(" und erhält "))
         .append(Component.literal(itemName).withStyle(WEAPON)));
   }

   /** Tod ohne Gegner: Sturz, Void, die eigene Ladung. */
   public static void death(ServerPlayer victim) {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server == null) {
         return;
      }
      broadcast(server, prefix("☠", VICTIM)
         .append(name(victim, VICTIM))
         .append(text(" ist gestorben")));
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
