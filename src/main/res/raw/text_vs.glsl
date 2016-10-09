uniform mat4 u_m4Matrix;
attribute vec4 a_v4Position;
attribute vec2 a_v2TexCoord;
varying vec2 texCoord;

void main()
{
    gl_Position = u_m4Matrix * a_v4Position;
    texCoord = a_v2TexCoord;
}
