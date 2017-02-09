precision mediump float;

varying vec2 v_v2ModelPosition;
varying vec4 v_v4Position;

void main()
{
    if (length(v_v2ModelPosition) > 1.0) //|| (abs(v_v2ModelPosition.x) < 0.05) || (abs(v_v2ModelPosition.y) < 0.05))
        discard;

	vec4 bitSh = vec4(256.0*256.0*256.0, 256.0*256.0, 256.0, 1.0);
	vec4 bitMsk = vec4(0, 1.0/256.0, 1.0/256.0, 1.0/256.0);
    float depth = (((v_v4Position.z / v_v4Position.w) + 1.0) / 2.0);
	vec4 c = fract(depth * bitSh);
	c -= c.xxyz * bitMsk;
    gl_FragColor = c;

    //gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
}
