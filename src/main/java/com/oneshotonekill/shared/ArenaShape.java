package com.oneshotonekill.shared;

import java.util.Arrays;

/**
 * Geometrie und räumliche Grenzen einer Arena.
 * <p>
 * <p>Enthält das Schnittstellen-Protokoll sowie die konkreten Implementierungen
 * für Polygone (Punkt-in-Polygon Raycasting mit Rastermaske), Quader (Box)
 * und die fest vermessenen Umrissdaten der drei Standard-Karten.</p>
 */
public sealed interface ArenaShape permits ArenaShape.Polygon, ArenaShape.Box {

    double ARENA_HEADROOM = 20.0;
    double ARENA_FLOOR_TOLERANCE = 2.0;

    static Polygon polygon(double minY, double maxY, int[] outline) {
        return Polygon.of(minY, maxY, outline);
    }

    static Box box(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Box.of(x1, y1, z1, x2, y2, z2);
    }

    double getMinX();

    double getMaxX();

    double getMinY();

    double getMaxY();

    double getMinZ();

    double getMaxZ();

    double getFootprint();

    boolean containsColumn(double x, double z);

    double[] getInwardNormal(double x, double z);

    default boolean contains(double x, double y, double z) {
        return containsColumn(x, z)
                && y >= getMinY() - ARENA_FLOOR_TOLERANCE
                && y <= getMaxY() + ARENA_HEADROOM;
    }

    /**
     * Polygonaler Kampfraum mit vorberechneter Rastermaske für schnelles Raycasting.
     */
    final class Polygon implements ArenaShape {
        private final double[] verticesX;
        private final double[] verticesZ;
        private final double minY, maxY, minX, maxX, minZ, maxZ;
        private final int width, depth;
        private volatile boolean[] mask;
        private volatile double footprint = -1.0;

        private Polygon(double[] verticesX, double[] verticesZ, double minY, double maxY) {
            this.verticesX = verticesX;
            this.verticesZ = verticesZ;
            this.minY = minY;
            this.maxY = maxY;
            this.minX = Arrays.stream(verticesX).min().orElseThrow();
            this.maxX = Arrays.stream(verticesX).max().orElseThrow();
            this.minZ = Arrays.stream(verticesZ).min().orElseThrow();
            this.maxZ = Arrays.stream(verticesZ).max().orElseThrow();
            this.width = (int) (maxX - minX) + 1;
            this.depth = (int) (maxZ - minZ) + 1;
        }

        public static Polygon of(double minY, double maxY, int[] xz) {
            if (xz.length < 6 || xz.length % 2 != 0) {
                throw new IllegalArgumentException("Ein Arena-Umriss braucht mindestens drei Punkte als X/Z-Paare");
            }
            int count = xz.length / 2;
            double[] x = new double[count];
            double[] z = new double[count];
            for (int i = 0; i < count; i++) {
                x[i] = xz[i * 2];
                z[i] = xz[i * 2 + 1];
            }
            return new Polygon(x, z, Math.min(minY, maxY), Math.max(minY, maxY));
        }

        @Override
        public double getMinX() {
            return minX;
        }

        @Override
        public double getMaxX() {
            return maxX;
        }

        @Override
        public double getMinY() {
            return minY;
        }

        @Override
        public double getMaxY() {
            return maxY;
        }

        @Override
        public double getMinZ() {
            return minZ;
        }

        @Override
        public double getMaxZ() {
            return maxZ;
        }

        @Override
        public double getFootprint() {
            double known = footprint;
            if (known >= 0.0) return known;
            synchronized (this) {
                if (footprint < 0.0) {
                    long count = 0;
                    for (boolean occupied : getMask()) if (occupied) count++;
                    footprint = count;
                }
                return footprint;
            }
        }

        @Override
        public boolean containsColumn(double x, double z) {
            int ix = (int) Math.floor(x) - (int) minX;
            int iz = (int) Math.floor(z) - (int) minZ;
            return ix >= 0 && ix < width && iz >= 0 && iz < depth && getMask()[ix * depth + iz];
        }

        @Override
        public double[] getInwardNormal(double x, double z) {
            double bestDistSq = Double.MAX_VALUE;
            double bestNx = 0.0;
            double bestNz = 0.0;
            for (int i = 0; i < verticesX.length; i++) {
                int j = (i + 1) % verticesX.length;
                double x1 = verticesX[i], z1 = verticesZ[i];
                double x2 = verticesX[j], z2 = verticesZ[j];
                double dx = x2 - x1, dz = z2 - z1;
                double lenSq = dx * dx + dz * dz;
                if (lenSq < 1.0E-8) continue;
                double t = Math.clamp(((x - x1) * dx + (z - z1) * dz) / lenSq, 0.0, 1.0);
                double projX = x1 + t * dx;
                double projZ = z1 + t * dz;
                double distSq = (x - projX) * (x - projX) + (z - projZ) * (z - projZ);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    double len = Math.sqrt(lenSq);
                    double n1x = -dz / len;
                    double n1z = dx / len;
                    double testDist = 0.5;
                    if (containsColumn(projX + n1x * testDist, projZ + n1z * testDist)) {
                        bestNx = n1x;
                        bestNz = n1z;
                    } else {
                        bestNx = -n1x;
                        bestNz = -n1z;
                    }
                }
            }
            if (bestNx == 0.0 && bestNz == 0.0) {
                double cx = (minX + maxX) * 0.5 - x;
                double cz = (minZ + maxZ) * 0.5 - z;
                double clen = Math.hypot(cx, cz);
                return clen > 1.0E-4 ? new double[]{cx / clen, cz / clen} : new double[]{0.0, 1.0};
            }
            return new double[]{bestNx, bestNz};
        }

        private boolean[] getMask() {
            boolean[] known = mask;
            if (known != null) return known;
            synchronized (this) {
                if (mask == null) mask = buildMask();
                return mask;
            }
        }

        private boolean[] buildMask() {
            boolean[] result = new boolean[width * depth];
            for (int ix = 0; ix < width; ix++) {
                for (int iz = 0; iz < depth; iz++) {
                    if (isInsideOutline(minX + ix + 0.5, minZ + iz + 0.5)) result[ix * depth + iz] = true;
                }
            }
            for (int i = 0; i < verticesX.length; i++) {
                int j = (i + 1) % verticesX.length;
                markSegment(result, verticesX[i], verticesZ[i], verticesX[j], verticesZ[j]);
            }
            return result;
        }

        private void markSegment(boolean[] target, double x1, double z1, double x2, double z2) {
            int steps = Math.max((int) (Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1)) * 2), 1);
            for (int step = 0; step <= steps; step++) {
                double share = (double) step / steps;
                int ix = (int) Math.floor(x1 + (x2 - x1) * share) - (int) minX;
                int iz = (int) Math.floor(z1 + (z2 - z1) * share) - (int) minZ;
                if (ix >= 0 && ix < width && iz >= 0 && iz < depth) target[ix * depth + iz] = true;
            }
        }

        private boolean isInsideOutline(double x, double z) {
            boolean inside = false;
            for (int i = 0, j = verticesX.length - 1; i < verticesX.length; j = i++) {
                double zi = verticesZ[i], zj = verticesZ[j];
                if ((zi > z) != (zj > z)) {
                    double crossX = (verticesX[j] - verticesX[i]) * (z - zi) / (zj - zi) + verticesX[i];
                    if (x < crossX) inside = !inside;
                }
            }
            return inside;
        }
    }

    /**
     * Quaderförmige Kampfzone.
     */
    final class Box implements ArenaShape {
        private final double minX, maxX, minY, maxY, minZ, maxZ;

        private Box(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        public static Box of(double x1, double y1, double z1, double x2, double y2, double z2) {
            return new Box(Math.min(x1, x2), Math.max(x1, x2), Math.min(y1, y2), Math.max(y1, y2), Math.min(z1, z2), Math.max(z1, z2));
        }

        @Override
        public double getMinX() {
            return minX;
        }

        @Override
        public double getMaxX() {
            return maxX;
        }

        @Override
        public double getMinY() {
            return minY;
        }

        @Override
        public double getMaxY() {
            return maxY;
        }

        @Override
        public double getMinZ() {
            return minZ;
        }

        @Override
        public double getMaxZ() {
            return maxZ;
        }

        @Override
        public double getFootprint() {
            return (maxX - minX + 1.0) * (maxZ - minZ + 1.0);
        }

        @Override
        public boolean containsColumn(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        @Override
        public double[] getInwardNormal(double x, double z) {
            double dMinX = Math.abs(x - minX);
            double dMaxX = Math.abs(x - maxX);
            double dMinZ = Math.abs(z - minZ);
            double dMaxZ = Math.abs(z - maxZ);
            double min = Math.min(Math.min(dMinX, dMaxX), Math.min(dMinZ, dMaxZ));
            if (min == dMinX) return new double[]{1.0, 0.0};
            if (min == dMaxX) return new double[]{-1.0, 0.0};
            if (min == dMinZ) return new double[]{0.0, 1.0};
            return new double[]{0.0, -1.0};
        }
    }

    /**
     * Feste x/z-Polygonzüge der Karten.
     */
    final class Outlines {
        public static final int[] STANDARD = {
                287, -106, 287, -51, 222, -51, 222, -106
        };

        public static final int[] DUSTPVP = {
                25, 33, -25, 33, -25, -33, 25, -33
        };

        public static final int[] BO2 = {
                -1147, 405, -1147, 377, -1139, 377, -1139, 379, -1137, 379, -1137, 377,
                -1126, 377, -1126, 379, -1123, 379, -1123, 377, -1114, 377, -1114, 379,
                -1111, 379, -1111, 377, -1109, 377, -1109, 382, -1105, 382, -1105, 377,
                -1094, 377, -1094, 379, -1091, 379, -1091, 377, -1070, 377, -1070, 379,
                -1066, 379, -1066, 381, -1061, 381, -1061, 379, -1062, 379, -1060, 379,
                -1060, 361, -1029, 361, -1029, 374, -1028, 374, -1028, 376, -1024, 376,
                -1024, 374, -1015, 374, -1015, 369, -980, 369, -980, 375, -974, 375,
                -974, 369, -959, 369, -960, 369, -960, 371, -958, 371, -958, 370,
                -957, 370, -957, 369, -950, 369, -950, 386, -957, 386, -957, 385,
                -958, 385, -958, 384, -960, 384, -960, 386, -959, 386, -964, 386,
                -964, 389, -965, 389, -965, 396, -962, 396, -962, 403, -964, 403,
                -964, 406, -962, 406, -962, 414, -971, 414, -971, 411, -977, 411,
                -977, 414, -1007, 414, -1007, 415, -1008, 415, -1008, 416, -1009, 416,
                -1009, 418, -1010, 418, -1010, 419, -1011, 419, -1011, 421, -1012, 421,
                -1012, 422, -1013, 422, -1013, 424, -1014, 424, -1014, 425, -1015, 425,
                -1015, 427, -1016, 427, -1016, 428, -1017, 428, -1017, 430, -1018, 430,
                -1018, 429, -1019, 429, -1019, 428, -1020, 428, -1020, 427, -1021, 427,
                -1021, 426, -1022, 426, -1022, 425, -1023, 425, -1024, 425, -1024, 427,
                -1023, 427, -1023, 428, -1022, 428, -1022, 429, -1021, 429, -1021, 430,
                -1020, 430, -1020, 431, -1019, 431, -1019, 433, -1020, 433, -1020, 434,
                -1021, 434, -1021, 436, -1022, 436, -1022, 437, -1023, 437, -1023, 439,
                -1024, 439, -1024, 440, -1025, 440, -1025, 441, -1027, 441, -1027, 442,
                -1027, 440, -1045, 440, -1045, 438, -1048, 438, -1048, 440, -1048, 439,
                -1050, 439, -1050, 438, -1052, 438, -1052, 437, -1053, 437, -1053, 436,
                -1056, 436, -1056, 435, -1058, 435, -1058, 434, -1060, 434, -1060, 433,
                -1062, 433, -1062, 434, -1062, 435, -1061, 435, -1062, 435, -1062, 437,
                -1063, 437, -1063, 438, -1063, 437, -1064, 437, -1064, 436, -1065, 436,
                -1065, 435, -1066, 435, -1066, 434, -1067, 434, -1066, 434, -1066, 433,
                -1065, 433, -1065, 432, -1064, 432, -1064, 431, -1063, 431, -1063, 429,
                -1065, 429, -1065, 430, -1066, 430, -1066, 431, -1067, 431, -1067, 432,
                -1068, 432, -1068, 433, -1068, 432, -1069, 432, -1069, 429, -1072, 429,
                -1072, 430, -1072, 428, -1075, 428, -1075, 430, -1077, 430, -1077, 428,
                -1080, 428, -1080, 430, -1106, 430, -1106, 424, -1115, 424, -1115, 430,
                -1135, 430, -1135, 423, -1137, 423, -1135, 423, -1135, 417, -1137, 417,
                -1135, 417, -1135, 411, -1137, 411, -1137, 409, -1133, 409, -1133, 406,
                -1137, 406, -1129, 406, -1129, 405, -1128, 405, -1128, 403, -1129, 403,
                -1129, 402, -1137, 402, -1137, 401, -1139, 401, -1139, 405
        };
        public static final int[] TILTED_TOWERS = {
                -67, 293, -67, 290, -70, 290, -70, 289, -72, 289, -72, 288,
                -74, 288, -74, 287, -75, 287, -75, 286, -76, 286, -76, 285,
                -77, 285, -77, 283, -78, 283, -78, 281, -79, 281, -79, 278,
                -103, 278, -103, 255, -111, 255, -111, 244, -121, 244, -121, 243,
                -122, 243, -122, 232, -128, 232, -128, 217, -127, 217, -127, 216,
                -126, 216, -126, 215, -103, 215, -103, 214, -102, 214, -102, 213,
                -102, 193, -81, 193, -81, 172, -40, 172, -40, 213, 0, 213,
                0, 251, 25, 251, 25, 281, 32, 281, 32, 308, 31, 308,
                31, 309, 16, 309, 16, 312, 15, 312, 15, 313, 14, 313,
                14, 314, 12, 314, 12, 319, -15, 319, -15, 315, -36, 315,
                -36, 309, -42, 309, -42, 307, -53, 307, -53, 296, -58, 296,
                -58, 293
        };
        public static final int[] TILTED_TOWERS_LOWER = {
                -33, 281, -31, 281, -30, 281, -28, 281, -27, 281, -25, 281,
                -24, 281, -22, 281, -21, 281, -19, 281, -18, 281, 32, 281,
                32, 308, 31, 308, 31, 309, 16, 309, 16, 312, 15, 312,
                15, 313, 13, 313, 13, 290, 12, 290, 12, 289, -18, 289,
                -19, 289, -21, 289, -22, 289, -24, 289, -25, 289, -27, 289,
                -28, 289, -30, 289, -31, 289, -33, 289
        };


        private Outlines() {
        }
    }
}
