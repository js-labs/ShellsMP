precision mediump float;
uniform vec4 u_v4Color;
uniform vec2 u_v2Mesh;
varying vec2 v2Position;

void main()
{
    if ((mod(v2Position.x, u_v2Mesh.x) < 2.0) || (mod(v2Position.y, u_v2Mesh.y) < 2.0))
        gl_FragColor = vec4( u_v4Color.r+0.1, u_v4Color.g+0.1, u_v4Color.b+0.1, u_v4Color.a );
    else
        gl_FragColor = u_v4Color;
}
