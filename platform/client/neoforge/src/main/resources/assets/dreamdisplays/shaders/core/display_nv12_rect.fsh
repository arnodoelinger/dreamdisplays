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

// Zero-copy hardware surfaces: Y plane and interleaved UV plane are imported as
// GL_TEXTURE_RECTANGLE, so texture coordinates are converted to pixel coordinates.
uniform sampler2DRect Sampler0; // Y
uniform sampler2DRect Sampler1; // UV

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
    vec2 ySize = vec2(textureSize(Sampler0));
    vec2 p = texCoord0 * ySize;

    // BT.709 limited range for 8-bit NV12/420v. Brightness follows the I420 shader contract:
    // vertex color carries 0..2 brightness halved into normalized bytes.
    float y = (texture(Sampler0, p).r - 0.0625) * 1.164384;
    vec2 uv = texture(Sampler1, p * 0.5).rg - vec2(0.5);
    vec3 rgb = clamp(vec3(
        y + 1.792741 * uv.y,
        y - 0.213249 * uv.x - 0.532909 * uv.y,
        y + 2.112402 * uv.x
    ), 0.0, 1.0);

    vec4 color = vec4(rgb * vertexColor.rgb * 2.0, vertexColor.a) * ColorModulator;

    // Render-distance fog only: fades the display toward the fog color as it nears the view
    // distance, fully gone beyond it. No environmental fog, so nearby displays stay untinted.
    float fogValue = linear_fog(cylindricalVertexDistance, FogRenderDistanceStart, FogRenderDistanceEnd);
    // Fade out to transparency (not to the fog color) so a distant display simply disappears
    // instead of turning into a black rectangle.
    fragColor = vec4(color.rgb, color.a * (1.0 - fogValue));
}
