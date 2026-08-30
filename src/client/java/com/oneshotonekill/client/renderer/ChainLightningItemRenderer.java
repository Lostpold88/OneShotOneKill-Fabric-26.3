package com.oneshotonekill.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.MapCodec;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Prozedurales 3D-Modell des Kettenblitz-Items.
 * <p>
 * <p>Warum überhaupt ein eigener Renderer: Blockmodell-Elemente waren in der GUI unsichtbar und
 * ergaben bei schrägen Balken je nach Kontext Sterne oder Treppen. Hotbar, X-Menü, Hand und
 * Boden benutzen hier dieselbe Geometrie; nur die Anzeige-Transformation stammt aus dem
 * jeweiligen Item-Kontext.</p>
 * <p>
 * <p><b>Der Blitz ist jetzt ein Körper, keine Platte.</b> Vorher lag ein flacher Zickzack in der
 * XY-Ebene, der zwischen zwei festen Z-Werten ausgezogen war – von vorn ein Blitz, von der Seite
 * ein Brett. Jetzt läuft die Kette durch alle drei Achsen, und jedes Stück ist ein fünfseitiges
 * Prisma, das sich zu den Enden hin verjüngt. Aus jedem Winkel bleibt eine Zacke eine Zacke.</p>
 * <p>
 * <p>Beleuchtet wird nichts: {@code debugQuads} zeichnet reine Vertexfarben. Die Räumlichkeit
 * muss deshalb aus der Schattierung kommen, die {@link #sideShade} je Prismenseite selbst
 * aufträgt – ohne sie sähe der Körper flach aus, obwohl er keiner ist.</p>
 */
@SuppressWarnings({"NullableProblems", "SameParameterValue"})
public final class ChainLightningItemRenderer implements SpecialModelRenderer<Void> {
   private static final ChainLightningItemRenderer INSTANCE = new ChainLightningItemRenderer();

   /** Seiten je Prisma. Fünf sind ungerade – dadurch trifft nie eine Fläche flach die Kamera. */
   private static final int SIDES = 5;

   /**
    * Die Kette in normierten Itemkoordinaten.
    * <p>
    * Jeder Zug ist eine Folge von Knoten mit eigenem Radius. Die Tiefe schwankt bewusst um die
    * Mitte: liefe sie flach durch, wäre der Blitz von der Seite wieder ein Brett.
    */
   private static final Node[][] STRANDS = {
      {
         new Node(0.74F, 0.96F, 0.50F, 0.028F),
         new Node(0.46F, 0.71F, 0.63F, 0.062F),
         new Node(0.61F, 0.60F, 0.39F, 0.056F),
         new Node(0.37F, 0.41F, 0.56F, 0.060F),
         new Node(0.53F, 0.32F, 0.42F, 0.046F),
         new Node(0.23F, 0.05F, 0.53F, 0.022F),
      },
      {
         new Node(0.46F, 0.71F, 0.63F, 0.038F),
         new Node(0.25F, 0.79F, 0.44F, 0.030F),
         new Node(0.09F, 0.66F, 0.57F, 0.014F),
      },
      {
         new Node(0.37F, 0.41F, 0.56F, 0.038F),
         new Node(0.67F, 0.47F, 0.63F, 0.030F),
         new Node(0.88F, 0.33F, 0.45F, 0.014F),
      },
   };

   /** Entladungsknoten an den Astspitzen. */
   private static final Node[] SPARKS = {
      new Node(0.09F, 0.66F, 0.57F, 0.055F),
      new Node(0.88F, 0.33F, 0.45F, 0.055F),
   };

   private ChainLightningItemRenderer() {
   }

   @Override
   public void submit(Void argument, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                      int overlayCoords, boolean hasFoil, int outlineColor) {
      // debugQuads benutzt POSITION_COLOR auf dem normalen Ziel und funktioniert deshalb auch
      // im GUI-Picture-in-Picture-Renderer. RenderTypes.lightning schreibt ins Wetter-Ziel – in
      // der Welt richtig, in Hotbar und Menüs jedoch leer.
      collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
         Matrix4fc matrix = pose.pose();
         for (Node[] strand : STRANDS) {
            for (int index = 0; index < strand.length - 1; index++) {
               Node from = strand[index];
               Node to = strand[index + 1];
               // Erst der goldene Mantel, dann der weißglühende Kern in derselben Achse.
               prism(matrix, buffer, from, to, 1.0F, 1.00F, 0.62F, 0.05F, 0.95F);
               prism(matrix, buffer, from, to, 0.34F, 1.00F, 0.98F, 0.78F, 1.00F);
            }
         }
         for (Node spark : SPARKS) {
            star(matrix, buffer, spark);
         }
      });
   }

   /**
    * Ein sich verjüngendes Prisma zwischen zwei Knoten.
    * <p>
    * Die Querachsen werden aus der Achse selbst gewonnen: dazu wird derjenige Einheitsvektor
    * herangezogen, zu dem die Achse am wenigsten parallel steht. Nähme man immer denselben,
    * klappte der Querschnitt zusammen, sobald ein Stück zufällig in diese Richtung zeigt.
    */
   private static void prism(Matrix4fc matrix, VertexConsumer buffer, Node from, Node to, float thickness,
                             float red, float green, float blue, float alpha) {
      Vector3f axis = new Vector3f(to.x - from.x, to.y - from.y, to.z - from.z);
      if (axis.lengthSquared() < 1.0E-8F) {
         return;
      }
      axis.normalize();

      Vector3f helper = Math.abs(axis.y) < 0.85F ? new Vector3f(0.0F, 1.0F, 0.0F) : new Vector3f(1.0F, 0.0F, 0.0F);
      Vector3f across = new Vector3f(axis).cross(helper).normalize();
      Vector3f up = new Vector3f(across).cross(axis).normalize();

      float radiusFrom = from.radius * thickness;
      float radiusTo = to.radius * thickness;

      for (int side = 0; side < SIDES; side++) {
         float angleA = (float) (side * Math.PI * 2.0 / SIDES);
         float angleB = (float) ((side + 1) * Math.PI * 2.0 / SIDES);
         float shade = sideShade(side);

         float[] a0 = ring(from, across, up, radiusFrom, angleA);
         float[] b0 = ring(from, across, up, radiusFrom, angleB);
         float[] a1 = ring(to, across, up, radiusTo, angleA);
         float[] b1 = ring(to, across, up, radiusTo, angleB);

         // Beide Wicklungen: debugQuads schneidet nichts weg, aber eine Seitenfläche, die man
         // von innen sieht, soll dieselbe Farbe tragen wie von außen.
         quad(matrix, buffer, a0, b0, b1, a1, red * shade, green * shade, blue * shade, alpha);
         quad(matrix, buffer, a1, b1, b0, a0, red * shade, green * shade, blue * shade, alpha);
      }
   }

   /**
    * Helligkeit einer Prismenseite.
    * <p>
    * {@code debugQuads} kennt kein Licht, also wird hier von Hand schattiert. Ohne diesen
    * Unterschied verschmelzen die Seitenflächen zu einer Fläche und der Körper wirkt flach.
    */
   private static float sideShade(int side) {
      return 0.62F + 0.38F * (float) Math.abs(Math.cos(side * Math.PI * 2.0 / SIDES + 0.6));
   }

   private static float[] ring(Node node, Vector3f across, Vector3f up, float radius, float angle) {
      float cos = (float) Math.cos(angle) * radius;
      float sin = (float) Math.sin(angle) * radius;
      return new float[] {
         node.x + across.x * cos + up.x * sin,
         node.y + across.y * cos + up.y * sin,
         node.z + across.z * cos + up.z * sin,
      };
   }

   /** Ein kleiner Stern aus drei gekreuzten Prismen an einer Astspitze. */
   private static void star(Matrix4fc matrix, VertexConsumer buffer, Node centre) {
      float reach = centre.radius;
      float[][] axes = {{reach, 0.0F, 0.0F}, {0.0F, reach, 0.0F}, {0.0F, 0.0F, reach}};
      for (float[] axis : axes) {
         Node from = new Node(centre.x - axis[0], centre.y - axis[1], centre.z - axis[2], 0.013F);
         Node to = new Node(centre.x + axis[0], centre.y + axis[1], centre.z + axis[2], 0.013F);
         prism(matrix, buffer, from, to, 1.0F, 1.00F, 0.96F, 0.70F, 1.00F);
      }
   }

   private static void quad(Matrix4fc matrix, VertexConsumer buffer, float[] a, float[] b, float[] c, float[] d,
                            float red, float green, float blue, float alpha) {
      vertex(matrix, buffer, a, red, green, blue, alpha);
      vertex(matrix, buffer, b, red, green, blue, alpha);
      vertex(matrix, buffer, c, red, green, blue, alpha);
      vertex(matrix, buffer, d, red, green, blue, alpha);
   }

   private static void vertex(Matrix4fc matrix, VertexConsumer buffer, float[] point,
                              float red, float green, float blue, float alpha) {
      buffer.addVertex(matrix, point[0], point[1], point[2]).setColor(red, green, blue, alpha);
   }

   @Override
   public void getExtents(Consumer<Vector3fc> output) {
      output.accept(new Vector3f(0.02F, 0.0F, 0.30F));
      output.accept(new Vector3f(0.96F, 1.0F, 0.72F));
   }

   @Override
   public Void extractArgument(ItemStack stack) {
      return null;
   }

   public record Unbaked() implements SpecialModelRenderer.Unbaked<Void> {
      public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

      @Override
      public SpecialModelRenderer<Void> bake(SpecialModelRenderer.BakingContext context) {
         return INSTANCE;
      }

      @Override
      public MapCodec<Unbaked> type() {
         return MAP_CODEC;
      }
   }

   /** Ein Kettenknoten: Ort im Einheitswürfel des Items und Halbmesser des Prismas dort. */
   private record Node(float x, float y, float z, float radius) {
   }
}
