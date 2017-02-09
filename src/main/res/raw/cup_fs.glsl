precision mediump float;

uniform vec3 u_v3EyePosition;
uniform vec3 u_v3Light[2]; /* [0] - position, [1] - color */
uniform vec3 u_v3Color;
uniform mat4 u_m4ShadowMatrix;
uniform sampler2D u_shadowTexture;
varying vec4 v_v4Position;
varying vec3 v_v3Normal;

void main()
{
    vec3 v3Color = u_v3Color * 0.4;
    vec3 v3Position = v_v4Position.xyz;
    vec3 v3l = (u_v3Light[0] - v3Position);
    float nldp = dot(v_v3Normal, v3l);
    if (nldp > 0.0)
    {
        vec4 sc = (u_m4ShadowMatrix * v_v4Position);
        if (sc.w > 0.0)
        {
            sc /= sc.w;
            if ((sc.x >= 0.0) && (sc.x <= 1.0) && (sc.y >= 0.0) && (sc.y <= 1.0))
            {
                float distanceFromLight = texture2D(u_shadowTexture, sc.xy).z;
                if (distanceFromLight > (sc.z - 0.05))
                {
                    v3Color += u_v3Color * 0.4 * nldp / length(v3l);
                    vec3 v3h = (u_v3EyePosition - v3Position + v3l);
                    float nhdp = dot(v_v3Normal, v3h) / length(v3h);
                    if (nhdp > 0.0)
                        v3Color += 0.2 * u_v3Light[1] * pow(nhdp, 5.0);
                }
            }
        }
    }
    gl_FragColor = vec4(v3Color, 1.0);
}
