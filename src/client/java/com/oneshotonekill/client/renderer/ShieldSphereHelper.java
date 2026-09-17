package com.oneshotonekill.client.renderer;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

/**
 * Gemeinsamer Geometrie- und Mesh-Generator für sphärische Schild-Effekte
 * (z. B. {@link MagnetShieldRenderer} und {@link ReflectorShieldRenderer}).
 */
public final class ShieldSphereHelper {
   private ShieldSphereHelper() {
   }

   public record SurfaceVertex(float x, float y, float z, float red, float green, float blue, float alpha) {
   }

   public record NormalAndFacing(float nx, float ny, float nz, double facing) {
   }

   @FunctionalInterface
   public interface VertexSampler {
      SurfaceVertex sample(double lat, double lon, float radius, float time, float phase, float strength, Vec3 view);
   }

   public static NormalAndFacing normalAndFacing(double latitude, double longitude, Vec3 view) {
      double cosLat = Math.cos(latitude);
      float nx = (float) (cosLat * Math.cos(longitude));
      float ny = (float) Math.sin(latitude);
      float nz = (float) (cosLat * Math.sin(longitude));
      double facing = Math.abs(nx * view.x + ny * view.y + nz * view.z);
      return new NormalAndFacing(nx, ny, nz, facing);
   }

   public static void renderSphere(Matrix4fc pose, VertexConsumer buffer, int latSegments, int lonSegments,
                                   float time, float strength, Vec3 view, float radius, float phase,
                                   VertexSampler sampler) {
      for (int latitude = 0; latitude < latSegments; latitude++) {
         double lat0 = -Math.PI * 0.5 + latitude * Math.PI / latSegments;
         double lat1 = -Math.PI * 0.5 + (latitude + 1) * Math.PI / latSegments;
         for (int longitude = 0; longitude < lonSegments; longitude++) {
            double lon0 = longitude * Math.PI * 2.0 / lonSegments;
            double lon1 = (longitude + 1) * Math.PI * 2.0 / lonSegments;

            SurfaceVertex a = sampler.sample(lat0, lon0, radius, time, phase, strength, view);
            SurfaceVertex b = sampler.sample(lat1, lon0, radius, time, phase, strength, view);
            SurfaceVertex c = sampler.sample(lat1, lon1, radius, time, phase, strength, view);
            SurfaceVertex d = sampler.sample(lat0, lon1, radius, time, phase, strength, view);
            quad(pose, buffer, a, b, c, d);
            quad(pose, buffer, d, c, b, a);
         }
      }
   }

   public static void quad(Matrix4fc pose, VertexConsumer buffer, SurfaceVertex a, SurfaceVertex b,
                           SurfaceVertex c, SurfaceVertex d) {
      vertex(pose, buffer, a);
      vertex(pose, buffer, b);
      vertex(pose, buffer, c);
      vertex(pose, buffer, d);
   }

   public static void vertex(Matrix4fc pose, VertexConsumer buffer, SurfaceVertex vertex) {
      buffer.addVertex(pose, vertex.x, vertex.y, vertex.z)
         .setColor(vertex.red, vertex.green, vertex.blue, vertex.alpha);
   }
}
