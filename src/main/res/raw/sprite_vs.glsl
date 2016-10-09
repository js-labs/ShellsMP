uniform mat4 uMatrix;
attribute vec4 vPosition;
attribute vec2 vTextureMap;
varying vec2 textureMap;

void main()
{
    gl_Position = uMatrix * vPosition;
    textureMap = vTextureMap;
}
