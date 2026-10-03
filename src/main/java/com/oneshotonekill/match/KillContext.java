package com.oneshotonekill.match;

import net.minecraft.world.phys.Vec3;

/**
 * Was ein Abschuss außer der Todesursache noch mitbringt.
 *
 * @param primary ob das Opfer das direkt getroffene Ziel war (Kettenblitz: nur das zählt)
 * @param origin  Ort der Wirkung, wenn er sich vom Opfer unterscheidet (Einschlag einer Explosion), sonst {@code null}
 */
public record KillContext(boolean primary, Vec3 origin) {
   public static final KillContext DEFAULT = new KillContext(true, null);

   public static KillContext at(Vec3 origin) {
      return new KillContext(true, origin);
   }

   public static KillContext secondary() {
      return new KillContext(false, null);
   }
}
