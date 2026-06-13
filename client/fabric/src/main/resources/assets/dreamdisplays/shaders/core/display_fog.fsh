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

uniform sampler2D Sampler0;

in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

float linear_fog(float dist, float start, float end) {
    if (dist <= start) return 0.0;
    if (dist >= end) return 1.0;
    return (dist - start) / (end - start);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    color *= ColorModulator;

    // Render-distance fog only: fades the display toward the fog color as it nears the view
    // distance, fully gone beyond it. No environmental fog, so nearby displays stay untinted.
    float fogValue = linear_fog(cylindricalVertexDistance, FogRenderDistanceStart, FogRenderDistanceEnd);
    // Fade out to transparency (not to the fog color) so a distant display simply disappears
    // instead of turning into a black rectangle.
    fragColor = vec4(color.rgb, color.a * (1.0 - fogValue));
}
