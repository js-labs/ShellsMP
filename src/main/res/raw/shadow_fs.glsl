precision mediump float;

varying vec4 v_v4Position;

void main()
{
    vec4 bitSh = vec4(256.0*256.0*256.0, 256.0*256.0, 256.0, 1.0);
    vec4 bitMsk = vec4(0, 1.0/256.0, 1.0/256.0, 1.0/256.0);
    float depth = (((v_v4Position.z / v_v4Position.w) + 1.0) / 2.0);
    vec4 c = fract(depth * bitSh);
    c -= c.xxyz * bitMsk;
    gl_FragColor = c;
}
