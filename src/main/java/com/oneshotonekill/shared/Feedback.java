package com.oneshotonekill.shared;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Kurze Rückmeldungen der Spezial-Items – bewusst in der Actionbar statt im Chat. */
public final class Feedback {
   private Feedback() {
   }

   public static void actionBar(ServerPlayer player, String text) {
      player.sendSystemMessage(Component.literal(text), true);
   }

   public static void actionBar(ServerPlayer player, Component component) {
      player.sendSystemMessage(component, true);
   }
}
