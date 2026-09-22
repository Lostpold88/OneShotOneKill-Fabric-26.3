package com.oneshotonekill.item.runtime;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Orthonormal basis vectors (eye, look, right, up) computed from a player's view angle.
 */
public record ViewBasis(Vec3 eye, Vec3 look, Vec3 right, Vec3 up) {
   private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);

   public static ViewBasis of(ServerPlayer player) {
      Vec3 eye = player.getEyePosition();
      Vec3 look = player.getLookAngle().normalize();
      Vec3 right = look.cross(WORLD_UP);
      right = right.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();
      Vec3 up = right.cross(look).normalize();
      return new ViewBasis(eye, look, right, up);
   }
}
