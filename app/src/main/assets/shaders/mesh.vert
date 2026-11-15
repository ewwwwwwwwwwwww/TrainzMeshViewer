#version 300 es
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uModel;
out vec3 vNormal;
out vec2 vUv;
void main() {
    gl_Position = uMvp * vec4(aPosition, 1.0);
    vNormal = mat3(uModel) * aNormal;
    vUv = aUv;
}
