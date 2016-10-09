uniform mat4 uMatrix;
attribute vec4 vPosition;

void main()
{
    gl_Position = uMatrix * vPosition;
    gl_PointSize = 5.0;
}
