#version 300 es
precision mediump float;
in vec3 vNormal;
in vec2 vUv;
uniform sampler2D uTexture;
uniform vec4 uColor;
uniform int uUseTexture;
uniform int uLighting;
out vec4 fragColor;
void main() {
    vec4 color = uColor;
    if (uUseTexture == 1) color *= texture(uTexture, vUv);
    float light = 1.0;
    if (uLighting == 1) light = 0.28 + 0.72 * max(dot(normalize(vNormal), normalize(vec3(-0.4, 0.8, 0.5))), 0.0);
    fragColor = vec4(color.rgb * light, color.a);
}
