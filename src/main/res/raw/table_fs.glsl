precision mediump float;
uniform vec3 u_v3Color;
uniform vec3 u_v3Light[2]; /* [0] - position, [1] - color */
uniform vec3 u_v3EyePosition;
uniform vec2 u_v2Mesh;
varying vec2 v2Position;

void main()
{
    vec3 v3LightPosition = vec3(u_v3Light[0].x-v2Position.x, u_v3Light[0].y-v2Position.y, u_v3Light[0].z);
    vec3 v3EyePosition = vec3(u_v3EyePosition.x-v2Position.x, u_v3EyePosition.y-v2Position.y, u_v3EyePosition.z);
    vec3 v3h = normalize(v3EyePosition + v3LightPosition);
    vec3 v3n = vec3(0.0, 0.0, 1.0);
    vec3 v3c = (u_v3Color + u_v3Light[1]) / 2.0;

    vec3 ia = 0.2 * u_v3Color;
    vec3 id = 0.4 * v3c * dot(normalize(v3LightPosition), v3n);
    vec3 is = 0.3 * v3c * pow(dot(v3h, v3n), 30.0);

    vec3 res = (ia + id + is);

    if ((mod(v2Position.x, u_v2Mesh.x) < 2.0) || (mod(v2Position.y, u_v2Mesh.y) < 2.0))
        res += vec3(0.1, 0.1, 0.1);

    gl_FragColor = vec4(res.x, res.y, res.z, 1.0);
}
