#version 330

uniform sampler2D InSampler;
uniform sampler2D HistoryNearSampler;
uniform sampler2D HistoryFarSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 HistoryNearSize;
    vec2 HistoryFarSize;
};

layout(std140) uniform TemporalConfig {
    float Strength;
    float ChromaticOffset;
    float WarpDirection;
    float WaveCenter;
    float Warmth;
    float Atmosphere;
    float HistoryMix;
    float Pulse;
    vec2 Origin;
    float Elapsed;
    float Restoring;
};

out vec4 fragColor;

float gaussian(float value, float width) {
    return exp(-pow(value / max(width, 0.0001), 2.0));
}

void main() {
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 fromOrigin = texCoord - Origin;
    vec2 metric = vec2(fromOrigin.x * aspect, fromOrigin.y);
    float radius = length(metric);
    vec2 metricDirection = metric / max(radius, 0.001);
    vec2 direction = vec2(metricDirection.x / aspect, metricDirection.y);
    vec2 tangent = vec2(-direction.y / aspect, direction.x * aspect);
    float envelope = 1.0 - smoothstep(0.04, 0.92, radius);

    // Die Druckwelle zieht beim Start zur Quelle und läuft beim Wiederanlaufen nach außen.
    float shockRing = gaussian(radius - WaveCenter, 0.052 + Atmosphere * 0.015);
    float wake = sin((radius - WaveCenter) * 53.0 - Elapsed * 5.0) *
        gaussian(radius - WaveCenter, 0.22);
    float twist = sin(radius * 34.0 - Elapsed * 1.8) * envelope;
    float idleRipple = sin(radius * 46.0 - Elapsed * 4.2) * Atmosphere * 0.0012;
    vec2 displacement = direction * wake * WarpDirection * Strength * 0.023
        + tangent * twist * WarpDirection * Strength * 0.0055
        + direction * idleRipple;
    vec2 warped = clamp(texCoord + displacement, vec2(0.001), vec2(0.999));

    // Spektrale Brechung bleibt während der sieben Sekunden nur hauchdünn sichtbar.
    vec2 chroma = direction * ChromaticOffset * WarpDirection * (0.35 + envelope * 0.65);
    float red = texture(InSampler, clamp(warped + chroma, vec2(0.001), vec2(0.999))).r;
    float green = texture(InSampler, warped).g;
    float blue = texture(InSampler, clamp(warped - chroma, vec2(0.001), vec2(0.999))).b;
    vec3 colour = vec3(red, green, blue);

    // Echte vorherige Frames: Bewegungen hinterlassen zwei verschieden gefärbte Zeitschichten.
    vec2 historyDrift = direction * (0.0018 + Strength * 0.0032) * WarpDirection;
    vec3 nearHistory = texture(HistoryNearSampler,
        clamp(warped + historyDrift, vec2(0.001), vec2(0.999))).rgb;
    vec3 farHistory = texture(HistoryFarSampler,
        clamp(warped + historyDrift * 1.8 - tangent * 0.0014, vec2(0.001), vec2(0.999))).rgb;
    vec3 coldEcho = nearHistory * vec3(0.76, 0.91, 1.12);
    vec3 violetEcho = farHistory * vec3(1.05, 0.72, 1.14);
    vec3 history = mix(coldEcho, violetEcho, 0.42);
    colour = mix(colour, history, clamp(HistoryMix, 0.0, 0.42));

    // Daueratmosphäre: kalte Entsättigung, Randflimmern und ein schwacher Sekundentakt.
    float luminance = dot(colour, vec3(0.2126, 0.7152, 0.0722));
    vec3 coldGrade = mix(vec3(luminance), colour, 0.72) * vec3(0.91, 0.98, 1.08);
    colour = mix(colour, coldGrade, Atmosphere * (1.0 - Warmth) * 0.32);
    float edge = smoothstep(0.24, 0.76, length(texCoord - vec2(0.5)));
    float edgeFlicker = 0.5 + 0.5 * sin((texCoord.y + Elapsed * 0.19) * OutSize.y * 0.32);
    colour *= 1.0 - edge * Atmosphere * (0.055 + edgeFlicker * 0.025);
    colour += vec3(0.08, 0.42, 0.72) * shockRing * (0.16 + Strength * 0.16);

    // Feine Zeitrisse leuchten beim Start violett und beim Wiederanlauf weißgold.
    float angle = atan(metric.y, metric.x);
    float fractures = pow(abs(sin(angle * 7.0 + radius * 21.0 - Elapsed * 0.8)), 22.0)
        * envelope * clamp(Strength, 0.0, 1.2);
    vec3 coldEnergy = vec3(0.24, 0.66, 1.00);
    vec3 warmEnergy = vec3(1.00, 0.72, 0.28);
    vec3 energy = mix(coldEnergy, warmEnergy, Warmth);
    colour += mix(vec3(0.48, 0.16, 0.92), energy, 0.58) * fractures * 0.09;

    // Beim Ende wird die kalte Welt in einer warmen Welle zurück in die Gegenwart gezogen.
    vec3 warmGrade = colour * vec3(1.09, 1.015, 0.91) + warmEnergy * shockRing * 0.20;
    colour = mix(colour, warmGrade, Warmth * (0.18 + Restoring * 0.20));
    float snapFlash = Restoring * Pulse * gaussian(radius - WaveCenter, 0.09);
    colour += warmEnergy * snapFlash * 0.22;

    fragColor = vec4(colour, 1.0);
}
