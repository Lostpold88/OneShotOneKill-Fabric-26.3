#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
uniform sampler2D HistorySampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 HistorySize;
};

// Dieselbe Gruppe wie im Verzerrungsdurchgang, damit ein einziger Schreibvorgang je Bild beide
// Puffer bedient. Alle Felder muessen deklariert bleiben, sonst verschieben sich die Offsets.
layout(std140) uniform TemporalConfig {
    float Strength;
    float Chroma;
    float WarpDirection;
    float Warmth;
    float Atmosphere;
    float HistoryMix;
    float Pulse;
    float Restoring;
    vec2 Origin;
    float OriginFocus;
    float Elapsed;
    vec2 RingRadius;
    vec2 RingEnergy;
    float HistoryAlpha;
    float RadialBlur;
    float Pinch;
    float SweepAngle;
    float SweepEnergy;
    float TrailLength;
    float Grain;
    float RimGain;
};

layout(location = 0) out vec4 fragColor;

// Exponentiell gleitendes Mittel statt einer festen Bildverzoegerung.
//
// Zwei gespeicherte Vorgaengerbilder sind bei 60 fps 17 und 33 ms alt, bei 144 fps nur 7 und
// 14 ms - viel zu kurz fuer eine sichtbare Zeitspur, und die Spurlaenge haengt an der Bildrate.
// HistoryAlpha kommt stattdessen aus der echten Bildzeit (1 - exp(-dt / tau)); damit ist die
// Spur bei jeder Bildrate gleich lang. In den ersten Bildern einer Aktivierung steht Alpha auf
// 1.0, wodurch der Puffer den Inhalt der vorherigen Zeitlupe in einem Zug ueberschreibt.
void main() {
    vec3 current = texture(InSampler, texCoord).rgb;
    vec3 history = texture(HistorySampler, texCoord).rgb;
    fragColor = vec4(mix(history, current, clamp(HistoryAlpha, 0.0, 1.0)), 1.0);
}
