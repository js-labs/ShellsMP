uniform mat4 uMatrix;
attribute vec4 a_v4Position;
varying vec2 v2Position;

void main()
{
    v2Position = a_v4Position.xy;
    gl_Position = uMatrix * a_v4Position;
}
