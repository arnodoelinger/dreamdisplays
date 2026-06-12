#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

// Zero-copy hardware surfaces: Y plane and interleaved UV plane are imported as
// GL_TEXTURE_RECTANGLE, so texture coordinates are converted to pixel coordinates.
uniform sampler2DRect Sampler0; // Y
uniform sampler2DRect Sampler1; // UV

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

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
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
