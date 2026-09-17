package com.oneshotonekill.client.effect;

import net.minecraft.client.model.HumanoidModel;

/** Seventies disco retargeted to Minecraft's six rigid limbs: diagonal points, hip swings,
 * crossed arms and alternating steps. The skin's sleeves, jacket and armour follow their parents.
 */
public final class BoogieDanceAnimation {
    private BoogieDanceAnimation() {}

    // Eight beats at 120 BPM. Angles in degrees: right X/Y/Z, left X/Y/Z.
    private static final float[][] ARMS = {
        {-150, -12, 38, -30, 12, -48},
        {-42, -20, -32, -65, -14, -28},
        {-155, -10, 42, -25, 10, -48},
        {-45, -18, -30, -70, -18, -25},
        {-28, -10, 48, -152, 12, -40},
        {-68, 16, 26, -42, 20, 32},
        {-30, -12, 48, -155, 10, -42},
        {-85, -32, -32, -85, 32, 32}
    };

    public static final float SALTO_START = 4.8F;
    public static final float SALTO_DURATION = 1.35F;

    public static final float SPIN_START = 6.15F;
    public static final float SPIN_DURATION = 1.15F;

    public static final float HUSTLE_START = 7.30F;
    public static final float HUSTLE_END = 11.0F;

    public static final float FINALE_START = 11.0F;

    public static float saltoProgress(float seconds) {
        if (seconds < SALTO_START || seconds > SALTO_START + SALTO_DURATION) return -1.0F;
        return (seconds - SALTO_START) / SALTO_DURATION;
    }

    public static float spinProgress(float seconds) {
        if (seconds < SPIN_START || seconds > SPIN_START + SPIN_DURATION) return -1.0F;
        return (seconds - SPIN_START) / SPIN_DURATION;
    }

    public static float spinYaw(float progress) {
        if (progress <= 0.0F) return 0.0F;
        if (progress >= 1.0F) return 360.0F;
        float ease = progress * progress * (3.0F - 2.0F * progress);
        return 360.0F * ease;
    }

    public static float hustleWeight(float seconds) {
        if (seconds < HUSTLE_START || seconds > HUSTLE_END) return 0.0F;
        float blendIn = Math.clamp((seconds - HUSTLE_START) / 0.40F, 0.0F, 1.0F);
        float blendOut = Math.clamp((HUSTLE_END - seconds) / 0.40F, 0.0F, 1.0F);
        float target = Math.min(blendIn, blendOut);
        return target * target * (3.0F - 2.0F * target);
    }

    public static float finaleWeight(float seconds) {
        if (seconds < FINALE_START) return 0.0F;
        float blendIn = Math.clamp((seconds - FINALE_START) / 0.45F, 0.0F, 1.0F);
        return blendIn * blendIn * (3.0F - 2.0F * blendIn);
    }

    public static float saltoJumpY(float progress) {
        if (progress <= 0.0F || progress >= 1.0F) return 0.0F;
        if (progress < 0.14F) {
            float t = progress / 0.14F;
            return -0.12F * (float) Math.sin(t * Math.PI);
        } else if (progress < 0.86F) {
            float fp = (progress - 0.14F) / 0.72F;
            return 1.20F * (float) Math.sin(fp * Math.PI);
        } else {
            float lp = (progress - 0.86F) / 0.14F;
            return -0.12F * (1.0F - lp) * (float) Math.sin(lp * Math.PI);
        }
    }

    public static float saltoPitch(float progress) {
        if (progress <= 0.12F) return 0.0F;
        if (progress >= 0.88F) return 360.0F;
        float fp = (progress - 0.12F) / 0.76F;
        float ease = fp * fp * fp * (fp * (fp * 6.0F - 15.0F) + 10.0F);
        return 360.0F * ease;
    }

    public static float saltoBallOffset(float seconds) {
        float p = saltoProgress(seconds);
        if (p < 0.10F || p > 0.90F) return 0.0F;
        float fp = (p - 0.10F) / 0.80F;
        return 0.95F * (float) Math.sin(fp * Math.PI);
    }

    private static void transformSocket(float lx, float ly,
                                        float bx, float by, float bz,
                                        float xRot, float yRot, float zRot,
                                        float[] out) {
        float cosZ = (float) Math.cos(zRot);
        float sinZ = (float) Math.sin(zRot);
        float x1 = lx * cosZ - ly * sinZ;
        float y1 = lx * sinZ + ly * cosZ;

        float cosY = (float) Math.cos(yRot);
        float sinY = (float) Math.sin(yRot);
        float x2 = x1 * cosY;
        float z2 = -x1 * sinY;

        float cosX = (float) Math.cos(xRot);
        float sinX = (float) Math.sin(xRot);
        float y3 = y1 * cosX - z2 * sinX;
        float z3 = y1 * sinX + z2 * cosX;

        out[0] = bx + x2;
        out[1] = by + y3;
        out[2] = bz + z3;
    }

    private static float lerpSmooth(float a, float b, float t) {
        float st = t * t * (3.0F - 2.0F * t);
        return a + (b - a) * st;
    }

    public static void pose(HumanoidModel<?> model, float seconds, float weight) {
        if (weight <= 0.0001F) return;

        // Ursprüngliche Vanilla-Pose zwischenspeichern (Gehen, Stehen, Blickrichtung)
        float vHeadX = model.head.x, vHeadY = model.head.y, vHeadZ = model.head.z;
        float vHeadXRot = model.head.xRot, vHeadYRot = model.head.yRot, vHeadZRot = model.head.zRot;

        float vBodyX = model.body.x, vBodyY = model.body.y, vBodyZ = model.body.z;
        float vBodyXRot = model.body.xRot, vBodyYRot = model.body.yRot, vBodyZRot = model.body.zRot;

        float vRArmX = model.rightArm.x, vRArmY = model.rightArm.y, vRArmZ = model.rightArm.z;
        float vRArmXRot = model.rightArm.xRot, vRArmYRot = model.rightArm.yRot, vRArmZRot = model.rightArm.zRot;

        float vLArmX = model.leftArm.x, vLArmY = model.leftArm.y, vLArmZ = model.leftArm.z;
        float vLArmXRot = model.leftArm.xRot, vLArmYRot = model.leftArm.yRot, vLArmZRot = model.leftArm.zRot;

        float vRLegX = model.rightLeg.x, vRLegY = model.rightLeg.y, vRLegZ = model.rightLeg.z;
        float vRLegXRot = model.rightLeg.xRot, vRLegYRot = model.rightLeg.yRot, vRLegZRot = model.rightLeg.zRot;

        float vLLegX = model.leftLeg.x, vLLegY = model.leftLeg.y, vLLegZ = model.leftLeg.z;
        float vLLegXRot = model.leftLeg.xRot, vLLegYRot = model.leftLeg.yRot, vLLegZRot = model.leftLeg.zRot;

        // Basis-Pivots laden für die Disco-Offset-Berechnung
        model.head.resetPose(); model.body.resetPose();
        model.rightArm.resetPose(); model.leftArm.resetPose();
        model.rightLeg.resetPose(); model.leftLeg.resetPose();

        float p = saltoProgress(seconds);
        float saltoBlend = 0.0F;
        float sHeadXRot = 0.0F, sBodyXRot = 0.0F;
        float sRArmXRot = 0.0F, sRArmYRot = 0.0F, sRArmZRot = 0.0F;
        float sLArmXRot = 0.0F, sLArmYRot = 0.0F, sLArmZRot = 0.0F;
        float sRLegXRot = 0.0F, sRLegZRot = 0.0F;
        float sLLegXRot = 0.0F, sLLegZRot = 0.0F;

        if (p >= 0.0F && p <= 1.0F) {
            saltoBlend = Math.clamp((float) Math.sin(p * Math.PI) * 1.35F, 0.0F, 1.0F);
            if (p < 0.16F) {
                // Phase A: Sanftes Absprung-Einknicken & Schwungholen
                float t = p / 0.16F;
                sHeadXRot = lerpSmooth(0.0F, -0.10F, t);
                sBodyXRot = lerpSmooth(0.0F, 0.10F, t);
                sRArmXRot = lerpSmooth(0.0F, 0.55F, t);
                sLArmXRot = lerpSmooth(0.0F, 0.55F, t);
                sRArmZRot = lerpSmooth(0.0F, 0.20F, t);
                sLArmZRot = lerpSmooth(0.0F, -0.20F, t);
                sRLegXRot = lerpSmooth(0.0F, -0.35F, t);
                sLLegXRot = lerpSmooth(0.0F, -0.35F, t);
            } else if (p < 0.35F) {
                // Phase B: Nahtloser Übergang in die kompakte Turner-Hocke (Tuck)
                float t = (p - 0.16F) / 0.19F;
                sHeadXRot = lerpSmooth(-0.10F, -0.32F, t);
                sBodyXRot = lerpSmooth(0.10F, -0.20F, t);
                sRArmXRot = lerpSmooth(0.55F, -1.15F, t);
                sLArmXRot = lerpSmooth(0.55F, -1.15F, t);
                sRArmYRot = lerpSmooth(0.0F, -0.20F, t);
                sLArmYRot = lerpSmooth(0.0F, 0.20F, t);
                sRArmZRot = lerpSmooth(0.20F, 0.35F, t);
                sLArmZRot = lerpSmooth(-0.20F, -0.35F, t);
                sRLegXRot = lerpSmooth(-0.35F, -0.85F, t);
                sLLegXRot = lerpSmooth(-0.35F, -0.85F, t);
                sRLegZRot = lerpSmooth(0.0F, 0.10F, t);
                sLLegZRot = lerpSmooth(0.0F, -0.10F, t);
            } else if (p < 0.68F) {
                // Phase C: Gehaltene Tuck-Pose durch den Zenit der 360°-Drehung
                sHeadXRot = -0.32F;
                sBodyXRot = -0.20F;
                sRArmXRot = -1.15F;
                sLArmXRot = -1.15F;
                sRArmYRot = -0.20F;
                sLArmYRot = 0.20F;
                sRArmZRot = 0.35F;
                sLArmZRot = -0.35F;
                sRLegXRot = -0.85F;
                sLLegXRot = -0.85F;
                sRLegZRot = 0.10F;
                sLLegZRot = -0.10F;
            } else if (p < 0.85F) {
                // Phase D: Beine strecken & Arme öffnen zur Landung
                float t = (p - 0.68F) / 0.17F;
                sHeadXRot = lerpSmooth(-0.32F, 0.0F, t);
                sBodyXRot = lerpSmooth(-0.20F, 0.08F, t);
                sRArmXRot = lerpSmooth(-1.15F, -0.25F, t);
                sLArmXRot = lerpSmooth(-1.15F, -0.25F, t);
                sRArmYRot = lerpSmooth(-0.20F, 0.0F, t);
                sLArmYRot = lerpSmooth(0.20F, 0.0F, t);
                sRArmZRot = lerpSmooth(0.35F, 0.55F, t);
                sLArmZRot = lerpSmooth(-0.35F, -0.55F, t);
                sRLegXRot = lerpSmooth(-0.85F, -0.25F, t);
                sLLegXRot = lerpSmooth(-0.85F, -0.25F, t);
                sRLegZRot = lerpSmooth(0.10F, 0.04F, t);
                sLLegZRot = lerpSmooth(-0.10F, -0.04F, t);
            } else {
                // Phase E: Weiches Abfedern und Übergang zurück in den Tanz
                float t = (p - 0.85F) / 0.15F;
                float damp = 1.0F - t * t * (3.0F - 2.0F * t);
                sBodyXRot = 0.08F * damp;
                sRArmXRot = -0.25F * damp;
                sLArmXRot = -0.25F * damp;
                sRArmZRot = 0.55F * damp;
                sLArmZRot = -0.55F * damp;
                sRLegXRot = -0.25F * damp;
                sLLegXRot = -0.25F * damp;
                sRLegZRot = 0.04F * damp;
                sLLegZRot = -0.04F * damp;
            }
        }

        float discoGrooveFactor = 1.0F - saltoBlend;
        float beat = seconds * 2.0F;
        float swing = (float) Math.sin(beat * Math.PI) * discoGrooveFactor;
        float bounce = (1.0F - (float) Math.cos(beat * Math.PI * 2)) * 0.60F * discoGrooveFactor;
        float hip = swing * 1.35F;

        // Phase 3A: 360° Boden-Pirouette
        float spinP = spinProgress(seconds);
        float spinBlend = 0.0F;
        if (spinP >= 0.0F && spinP <= 1.0F) {
            spinBlend = Math.clamp((float) Math.sin(spinP * Math.PI) * 1.30F, 0.0F, 1.0F);
        }

        // Phase 3B: The Hustle / Rolling Wheels
        float hw = hustleWeight(seconds);
        float rollAngle = (seconds - HUSTLE_START) * 2.0F * (float) (Math.PI * 2.0);
        float rollSin = (float) Math.sin(rollAngle);
        float rollCos = (float) Math.cos(rollAngle);
        float hRArmX = -1.25F + rollSin * 0.28F;
        float hRArmY = -0.38F + rollCos * 0.22F;
        float hRArmZ = 0.48F + rollCos * 0.16F;
        float hLArmX = -1.25F - rollSin * 0.28F;
        float hLArmY = 0.38F - rollCos * 0.22F;
        float hLArmZ = -0.48F - rollCos * 0.16F;
        float shimmy = (float) Math.sin(beat * Math.PI) * 0.10F;

        // Phase 4: Hands in the Air Grand Finale
        float fw = finaleWeight(seconds);
        float partyPump = (1.0F - (float) Math.cos(beat * Math.PI * 2.0)) * 0.18F;
        float fRArmX = -2.75F + partyPump;
        float fRArmY = -0.15F;
        float fRArmZ = 0.45F - partyPump * 0.12F;
        float fLArmX = -2.75F + partyPump;
        float fLArmY = 0.15F;
        float fLArmZ = -0.45F + partyPump * 0.12F;
        float fHeadX = -0.22F + (float) Math.sin(beat * Math.PI * 2.0) * 0.10F;

        // Ziel-Posen der Disco-Choreografie
        float spinCalm = 1.0F - spinBlend;
        float dBodyX = model.body.x + hip * 0.45F * spinCalm;
        float dBodyY = model.body.y + bounce * spinCalm;
        float dBodyZ = model.body.z + (1.0F - (float) Math.cos(beat * Math.PI * 2)) * 0.20F * discoGrooveFactor * spinCalm;
        float dBodyXRot = ((0.05F + (float) Math.sin(beat * Math.PI * 2) * 0.03F) * discoGrooveFactor + sBodyXRot * saltoBlend) * spinCalm;
        float dBodyYRot = swing * 0.22F * spinCalm;
        float dBodyZRot = (-swing * 0.08F + shimmy * hw) * spinCalm;

        float[] socket = new float[3];

        transformSocket(0.0F, 0.0F, dBodyX, dBodyY, dBodyZ, dBodyXRot, dBodyYRot, dBodyZRot, socket);
        float dHeadX = socket[0], dHeadY = socket[1], dHeadZ = socket[2];
        float dHeadXRot = (float) Math.sin(beat * Math.PI * 2.0) * 0.12F * discoGrooveFactor;
        float dHeadYRot = -swing * 0.20F;
        float dHeadZRot = swing * 0.06F;

        if (spinBlend > 0.001F) {
            dHeadXRot = net.minecraft.util.Mth.lerp(spinBlend, dHeadXRot, 0.0F);
            dHeadYRot = net.minecraft.util.Mth.lerp(spinBlend, dHeadYRot, 0.0F);
            dHeadZRot = net.minecraft.util.Mth.lerp(spinBlend, dHeadZRot, 0.0F);
        }

        transformSocket(-5.0F, 2.0F, dBodyX, dBodyY, dBodyZ, dBodyXRot, dBodyYRot, dBodyZRot, socket);
        float dRArmX = socket[0], dRArmY = socket[1], dRArmZ = socket[2];

        transformSocket(5.0F, 2.0F, dBodyX, dBodyY, dBodyZ, dBodyXRot, dBodyYRot, dBodyZRot, socket);
        float dLArmX = socket[0], dLArmY = socket[1], dLArmZ = socket[2];

        float phase = beat % ARMS.length;
        int first = (int) phase;
        int second = (first + 1) % ARMS.length;
        float blend = phase - first;
        blend = blend * blend * (3 - 2 * blend);

        float radians = (float) (Math.PI / 180);
        // Phase 1: Travolta Points
        float dRArmXRot = (ARMS[first][0] + (ARMS[second][0] - ARMS[first][0]) * blend) * radians;
        float dRArmYRot = (ARMS[first][1] + (ARMS[second][1] - ARMS[first][1]) * blend) * radians;
        float dRArmZRot = (ARMS[first][2] + (ARMS[second][2] - ARMS[first][2]) * blend) * radians;

        float dLArmXRot = (ARMS[first][3] + (ARMS[second][3] - ARMS[first][3]) * blend) * radians;
        float dLArmYRot = (ARMS[first][4] + (ARMS[second][4] - ARMS[first][4]) * blend) * radians;
        float dLArmZRot = (ARMS[first][5] + (ARMS[second][5] - ARMS[first][5]) * blend) * radians;

        // Phase 3A: Flotte Pirouette (Arme elegant angezogen)
        if (spinBlend > 0.001F) {
            float spinRArmX = -0.80F, spinRArmY = -0.30F, spinRArmZ = 0.45F;
            float spinLArmX = -0.80F, spinLArmY = 0.30F, spinLArmZ = -0.45F;
            dRArmXRot = net.minecraft.util.Mth.lerp(spinBlend, dRArmXRot, spinRArmX);
            dRArmYRot = net.minecraft.util.Mth.lerp(spinBlend, dRArmYRot, spinRArmY);
            dRArmZRot = net.minecraft.util.Mth.lerp(spinBlend, dRArmZRot, spinRArmZ);
            dLArmXRot = net.minecraft.util.Mth.lerp(spinBlend, dLArmXRot, spinLArmX);
            dLArmYRot = net.minecraft.util.Mth.lerp(spinBlend, dLArmYRot, spinLArmY);
            dLArmZRot = net.minecraft.util.Mth.lerp(spinBlend, dLArmZRot, spinLArmZ);
        }

        // Phase 3B: The Hustle / Rolling Wheels
        if (hw > 0.001F) {
            dRArmXRot = net.minecraft.util.Mth.lerp(hw, dRArmXRot, hRArmX);
            dRArmYRot = net.minecraft.util.Mth.lerp(hw, dRArmYRot, hRArmY);
            dRArmZRot = net.minecraft.util.Mth.lerp(hw, dRArmZRot, hRArmZ);
            dLArmXRot = net.minecraft.util.Mth.lerp(hw, dLArmXRot, hLArmX);
            dLArmYRot = net.minecraft.util.Mth.lerp(hw, dLArmYRot, hLArmY);
            dLArmZRot = net.minecraft.util.Mth.lerp(hw, dLArmZRot, hLArmZ);
        }

        // Phase 4: Grand Finale (Hands in the Air)
        if (fw > 0.001F) {
            dRArmXRot = net.minecraft.util.Mth.lerp(fw, dRArmXRot, fRArmX);
            dRArmYRot = net.minecraft.util.Mth.lerp(fw, dRArmYRot, fRArmY);
            dRArmZRot = net.minecraft.util.Mth.lerp(fw, dRArmZRot, fRArmZ);
            dLArmXRot = net.minecraft.util.Mth.lerp(fw, dLArmXRot, fLArmX);
            dLArmYRot = net.minecraft.util.Mth.lerp(fw, dLArmYRot, fLArmY);
            dLArmZRot = net.minecraft.util.Mth.lerp(fw, dLArmZRot, fLArmZ);
            dHeadXRot = net.minecraft.util.Mth.lerp(fw, dHeadXRot, fHeadX);
        }

        transformSocket(-1.9F, 12.0F, dBodyX, dBodyY, dBodyZ, dBodyXRot, dBodyYRot, dBodyZRot, socket);
        float stepOffset = swing * 0.18F * discoGrooveFactor * spinCalm;
        float dRLegX = socket[0], dRLegY = socket[1], dRLegZ = socket[2] + stepOffset;

        transformSocket(1.9F, 12.0F, dBodyX, dBodyY, dBodyZ, dBodyXRot, dBodyYRot, dBodyZRot, socket);
        float dLLegX = socket[0], dLLegY = socket[1], dLLegZ = socket[2] - stepOffset;

        float bounceBend = -bounce * 0.14F;
        float dRLegXRot = (Math.max(0.0F, swing) * -0.48F + bounceBend) * discoGrooveFactor;
        float dLLegXRot = (Math.max(0.0F, -swing) * -0.48F + bounceBend) * discoGrooveFactor;
        float dRLegYRot = (dBodyYRot * 0.85F + (swing > 0.0F ? 0.08F : -0.04F)) * discoGrooveFactor;
        float dLLegYRot = (dBodyYRot * 0.85F + (swing < 0.0F ? -0.08F : 0.04F)) * discoGrooveFactor;
        float dRLegZRot = (0.08F - swing * 0.15F + Math.max(0.0F, swing) * 0.05F) * discoGrooveFactor;
        float dLLegZRot = (-0.08F - swing * 0.15F - Math.max(0.0F, -swing) * 0.05F) * discoGrooveFactor;

        if (spinBlend > 0.001F) {
            dRLegXRot = net.minecraft.util.Mth.lerp(spinBlend, dRLegXRot, 0.0F);
            dLLegXRot = net.minecraft.util.Mth.lerp(spinBlend, dLLegXRot, 0.0F);
            dRLegYRot = net.minecraft.util.Mth.lerp(spinBlend, dRLegYRot, 0.0F);
            dLLegYRot = net.minecraft.util.Mth.lerp(spinBlend, dLLegYRot, 0.0F);
            dRLegZRot = net.minecraft.util.Mth.lerp(spinBlend, dRLegZRot, 0.0F);
            dLLegZRot = net.minecraft.util.Mth.lerp(spinBlend, dLLegZRot, 0.0F);
        }

        if (saltoBlend > 0.001F) {
            dHeadXRot = net.minecraft.util.Mth.lerp(saltoBlend, dHeadXRot, sHeadXRot);
            dHeadYRot = net.minecraft.util.Mth.lerp(saltoBlend, dHeadYRot, 0.0F);
            dHeadZRot = net.minecraft.util.Mth.lerp(saltoBlend, dHeadZRot, 0.0F);

            dRArmXRot = net.minecraft.util.Mth.lerp(saltoBlend, dRArmXRot, sRArmXRot);
            dRArmYRot = net.minecraft.util.Mth.lerp(saltoBlend, dRArmYRot, sRArmYRot);
            dRArmZRot = net.minecraft.util.Mth.lerp(saltoBlend, dRArmZRot, sRArmZRot);

            dLArmXRot = net.minecraft.util.Mth.lerp(saltoBlend, dLArmXRot, sLArmXRot);
            dLArmYRot = net.minecraft.util.Mth.lerp(saltoBlend, dLArmYRot, sLArmYRot);
            dLArmZRot = net.minecraft.util.Mth.lerp(saltoBlend, dLArmZRot, sLArmZRot);

            dRLegXRot = net.minecraft.util.Mth.lerp(saltoBlend, dRLegXRot, sRLegXRot);
            dRLegYRot = net.minecraft.util.Mth.lerp(saltoBlend, dRLegYRot, 0.0F);
            dRLegZRot = net.minecraft.util.Mth.lerp(saltoBlend, dRLegZRot, sRLegZRot);

            dLLegXRot = net.minecraft.util.Mth.lerp(saltoBlend, dLLegXRot, sLLegXRot);
            dLLegYRot = net.minecraft.util.Mth.lerp(saltoBlend, dLLegYRot, 0.0F);
            dLLegZRot = net.minecraft.util.Mth.lerp(saltoBlend, dLLegZRot, sLLegZRot);
        }

        // Kontinuierliche Interpolation zwischen Vanilla- und Disco-Pose
        model.head.x = net.minecraft.util.Mth.lerp(weight, vHeadX, dHeadX);
        model.head.y = net.minecraft.util.Mth.lerp(weight, vHeadY, dHeadY);
        model.head.z = net.minecraft.util.Mth.lerp(weight, vHeadZ, dHeadZ);
        model.head.xRot = net.minecraft.util.Mth.lerp(weight, vHeadXRot, dHeadXRot);
        model.head.yRot = net.minecraft.util.Mth.lerp(weight, vHeadYRot, dHeadYRot);
        model.head.zRot = net.minecraft.util.Mth.lerp(weight, vHeadZRot, dHeadZRot);

        model.body.x = net.minecraft.util.Mth.lerp(weight, vBodyX, dBodyX);
        model.body.y = net.minecraft.util.Mth.lerp(weight, vBodyY, dBodyY);
        model.body.z = net.minecraft.util.Mth.lerp(weight, vBodyZ, dBodyZ);
        model.body.xRot = net.minecraft.util.Mth.lerp(weight, vBodyXRot, dBodyXRot);
        model.body.yRot = net.minecraft.util.Mth.lerp(weight, vBodyYRot, dBodyYRot);
        model.body.zRot = net.minecraft.util.Mth.lerp(weight, vBodyZRot, dBodyZRot);

        model.rightArm.x = net.minecraft.util.Mth.lerp(weight, vRArmX, dRArmX);
        model.rightArm.y = net.minecraft.util.Mth.lerp(weight, vRArmY, dRArmY);
        model.rightArm.z = net.minecraft.util.Mth.lerp(weight, vRArmZ, dRArmZ);
        model.rightArm.xRot = net.minecraft.util.Mth.lerp(weight, vRArmXRot, dRArmXRot);
        model.rightArm.yRot = net.minecraft.util.Mth.lerp(weight, vRArmYRot, dRArmYRot);
        model.rightArm.zRot = net.minecraft.util.Mth.lerp(weight, vRArmZRot, dRArmZRot);

        model.leftArm.x = net.minecraft.util.Mth.lerp(weight, vLArmX, dLArmX);
        model.leftArm.y = net.minecraft.util.Mth.lerp(weight, vLArmY, dLArmY);
        model.leftArm.z = net.minecraft.util.Mth.lerp(weight, vLArmZ, dLArmZ);
        model.leftArm.xRot = net.minecraft.util.Mth.lerp(weight, vLArmXRot, dLArmXRot);
        model.leftArm.yRot = net.minecraft.util.Mth.lerp(weight, vLArmYRot, dLArmYRot);
        model.leftArm.zRot = net.minecraft.util.Mth.lerp(weight, vLArmZRot, dLArmZRot);

        model.rightLeg.x = net.minecraft.util.Mth.lerp(weight, vRLegX, dRLegX);
        model.rightLeg.y = net.minecraft.util.Mth.lerp(weight, vRLegY, dRLegY);
        model.rightLeg.z = net.minecraft.util.Mth.lerp(weight, vRLegZ, dRLegZ);
        model.rightLeg.xRot = net.minecraft.util.Mth.lerp(weight, vRLegXRot, dRLegXRot);
        model.rightLeg.yRot = net.minecraft.util.Mth.lerp(weight, vRLegYRot, dRLegYRot);
        model.rightLeg.zRot = net.minecraft.util.Mth.lerp(weight, vRLegZRot, dRLegZRot);

        model.leftLeg.x = net.minecraft.util.Mth.lerp(weight, vLLegX, dLLegX);
        model.leftLeg.y = net.minecraft.util.Mth.lerp(weight, vLLegY, dLLegY);
        model.leftLeg.z = net.minecraft.util.Mth.lerp(weight, vLLegZ, dLLegZ);
        model.leftLeg.xRot = net.minecraft.util.Mth.lerp(weight, vLLegXRot, dLLegXRot);
        model.leftLeg.yRot = net.minecraft.util.Mth.lerp(weight, vLLegYRot, dLLegYRot);
        model.leftLeg.zRot = net.minecraft.util.Mth.lerp(weight, vLLegZRot, dLLegZRot);
    }
}
