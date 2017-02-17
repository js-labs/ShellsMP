precision highp float;

uniform vec3 u_v3Color;
uniform vec3 u_v3Light[2]; /* [0] - position, [1] - color */
uniform vec3 u_v3EyePosition;
uniform vec2 u_v2Mesh;

#if defined(RENDER_SHADOWS)
uniform mat4 u_m4ShadowMatrix;
uniform sampler2D u_shadowTexture;
#endif

varying vec4 v_v4Position;

void main()
{
    vec3 v3LightPosition = u_v3Light[0] - vec3(v_v4Position.x, v_v4Position.y, v_v4Position.z);
    vec3 v3EyePosition = u_v3EyePosition - v_v4Position.xyz;
    vec3 v3h = normalize(v3EyePosition + v3LightPosition);
    vec3 v3n = vec3(0.0, 0.0, 1.0);

    vec3 c = 0.4 * u_v3Color; /* ambient */

#if defined(RENDER_SHADOWS)
    vec4 sc = (u_m4ShadowMatrix * v_v4Position);
    if (sc.w > 0.0)
    {
        sc /= sc.w;
        if ((sc.x >= 0.0) && (sc.x <= 1.0) && (sc.y >= 0.0) && (sc.y <= 1.0))
        {
            float distanceFromLight = texture2D(u_shadowTexture, sc.xy).z;
            if (distanceFromLight > sc.z)
            {
                vec3 v3c = (u_v3Color + u_v3Light[1]) / 2.0;
                c += 0.3 * v3c * dot(normalize(v3LightPosition), v3n);
                c += 0.3 * v3c * pow(dot(v3h, v3n), 30.0);
            }
        }
    }
#else
    vec3 v3c = (u_v3Color + u_v3Light[1]) / 2.0;
    c += 0.3 * v3c;
    c += 0.3 * v3c * dot(normalize(v3LightPosition), v3n);
    c += 0.3 * v3c * pow(dot(v3h, v3n), 30.0);
#endif

    if ((mod(v_v4Position.x, u_v2Mesh.x) < 2.0) || (mod(v_v4Position.y, u_v2Mesh.y) < 2.0))
        c += vec3(0.1, 0.1, 0.1);

    gl_FragColor = vec4(c.x, c.y, c.z, 1.0);
}
