#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

// I420 planes: Y at full resolution, U / V at half
uniform sampler2D Sampler0; // Y
uniform sampler2D Sampler1; // U
uniform sampler2D Sampler3; // V

in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

float linear_fog(float dist, float start, float end) {
    if (dist <= start) return 0.0;
    if (dist >= end) return 1.0;
    return (dist - start) / (end - start);
}

void main() {
    // BT.709 limited range; the FFmpeg filter chain pins the stream to bt709
    float y = (texture(Sampler0, texCoord0).r - 0.0625) * 1.164384;
    float u = texture(Sampler1, texCoord0).r - 0.5;
    float v = texture(Sampler3, texCoord0).r - 0.5;
    vec3 rgb = clamp(vec3(
        y + 1.792741 * v,
        y - 0.213249 * u - 0.532909 * v,
        y + 2.112402 * u
    ), 0.0, 1.0);

    // Brightness (0..2) arrives halved in the vertex color so it fits a normalized byte
    vec4 color = vec4(rgb * vertexColor.rgb * 2.0, vertexColor.a) * ColorModulator;

    // Render-distance fog only: fades the display toward the fog color as it nears the view
    // distance, fully gone beyond it. No environmental fog, so nearby displays stay untinted.
    float fogValue = linear_fog(cylindricalVertexDistance, FogRenderDistanceStart, FogRenderDistanceEnd);
    // Fade out to transparency (not to the fog color) so a distant display simply disappears
    // instead of turning into a black rectangle.
    fragColor = vec4(color.rgb, color.a * (1.0 - fogValue));
}
