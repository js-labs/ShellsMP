/*
 * Copyright (C) 2016 Sergey Zubarev, info@js-labs.org
 *
 * This file is a part of ShellsMP application.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jsl.shmp;

import android.content.Context;
import android.graphics.*;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Set;

public class Canvas3D
{
    public static final float [] s_identityMatrix = createIdentityMatrix();

    public enum Align
    {
        LEFT  (0), // The text is drawn to the left of the x,y origin
        CENTER(1), // The text is centered vertically on the x,y origin
        RIGHT (2); // the text is drawn to the left of the x,y origin

        final int value;
        Align(int value) { this.value = value; }
    }

    public enum VerticalAlign
    {
        UP    (0), // The text is drawn to the down of the x,y origin
        CENTER(1), // The text is centered vertically on the x,y origin
        DOWN  (2); // the text is drawn to the up of the x,y origin

        final int value;
        VerticalAlign(int value) { this.value = value; }
    }

    private static final float [] s_matrix = new float[32];

    private static float [] createIdentityMatrix()
    {
        final float [] matrix = new float[16];
        Matrix.setIdentityM(matrix, 0);
        return matrix;
    }

    private final SpriteDrawer m_spriteDrawer;
    private final LinesDrawer m_linesDrawer;
    private final TextDrawer m_textDrawer;

    private static String loadRawResource(Context context, int resourceId, Set<String> macro) throws IOException
    {
        final StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try
        {
            final InputStream inputStream = context.getResources().openRawResource(resourceId);
            reader = new BufferedReader(new InputStreamReader(inputStream));

            if (macro != null)
            {
                for(String s : macro)
                {
                    sb.append("#define ");
                    sb.append(s);
                    sb.append(" 1\r\n");
                }
            }

            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line);
                sb.append("\r\n");
            }
        }
        finally
        {
            if (reader != null)
                reader.close();
        }
        return sb.toString();
    }

    public static int createShader(Context context, int resourceId, int shaderType, Set<String> macro) throws IOException
    {
        final String shaderCode = loadRawResource(context, resourceId, macro);
        final int shaderId = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shaderId, shaderCode);
        GLES20.glCompileShader(shaderId);
        final int [] compileStatus = new int[1];
        GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] != GLES20.GL_TRUE)
        {
            final String infoLog = GLES20.glGetShaderInfoLog(shaderId);
            GLES20.glDeleteShader(shaderId);
            throw new IOException(infoLog);
        }
        return shaderId;
    }

    public static int createProgram(
            Context context, int vertexShaderResourceId, int fragmentShaderResourceId, Set<String> macro) throws IOException
    {
        final int vertexShaderId = createShader(context, vertexShaderResourceId, GLES20.GL_VERTEX_SHADER, macro);
        final int fragmentShaderId = createShader(context, fragmentShaderResourceId, GLES20.GL_FRAGMENT_SHADER, macro);

        final int programId = GLES20.glCreateProgram();
        GLES20.glAttachShader( programId, vertexShaderId );
        GLES20.glAttachShader( programId, fragmentShaderId );
        GLES20.glLinkProgram( programId );

        final int [] linkStatus = new int[1];
        GLES20.glGetProgramiv( programId, GLES20.GL_LINK_STATUS, linkStatus, 0 );
        if (linkStatus[0] != GLES20.GL_TRUE)
        {
            final String infoString = GLES20.glGetProgramInfoLog( programId );
            GLES20.glDeleteProgram( programId );
            throw new IOException( infoString );
        }

        return programId;
    }

    public static class Sprite
    {
        private final int m_textureId;
        private final FloatBuffer m_vertexData;
        private boolean m_hasAlpha;

        public float x;
        public float y;
        public float z;

        public Sprite() throws IOException
        {
            final int [] textureId = new int[1];
            GLES20.glGenTextures( 1, textureId , 0 );
            if (textureId[0] == 0)
                throw new IOException( "glGenTextures() failed" );
            m_textureId = textureId[0];

            final int bufferCapacity = (Float.SIZE/Byte.SIZE) * (3 * 4);
            final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bufferCapacity);
            byteBuffer.order(ByteOrder.nativeOrder());
            m_vertexData = byteBuffer.asFloatBuffer();
        }

        public void setBitmap(Bitmap bitmap)
        {
            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();
            if ((width > 0) && (height > 0))
            {
                final int right = (width - 1);
                final int bottom = -(height - 1);
                m_vertexData.position(0);
                m_vertexData.put(0f);
                m_vertexData.put(bottom);
                m_vertexData.put(0f);
                m_vertexData.put(0f);
                m_vertexData.put(right);
                m_vertexData.put(bottom);
                m_vertexData.put(right);
                m_vertexData.put(0f);

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, m_textureId);

                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

                m_hasAlpha = bitmap.hasAlpha();
                if (m_hasAlpha)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bitmap, 0);
                else
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            }
            else
                throw new AssertionError();
        }

        public void setCenterAt( float cx, float cy )
        {
            float width = (m_vertexData.get(6) + 1);
            float height = (-m_vertexData.get(4) + 1);
            x = (cx - (width / 2));
            y = (cy + (height / 2));
        }
    }

    private static class SpriteDrawer
    {
        private static final String SV_MATRIX = "u_m4Matrix";
        private static final String SV_POSITION = "a_v4Position";
        private static final String SV_TEXTURE_MAP = "a_v2TextureMap";
        private static final String SV_TEXTURE_UNIT = "u_sTextureUnit";
        private final int m_programId;
        private final int m_matrixLocation;
        private final int m_positionLocation;
        private final int m_textureMapLocation;
        private final int m_textureUnitLocation;
        private final FloatBuffer m_textureMap;

        public SpriteDrawer(Context context) throws IOException
        {
            final int vertexShaderId = createShader(context, R.raw.sprite_vs, GLES20.GL_VERTEX_SHADER, null);
            final int fragmentShaderId = createShader(context, R.raw.sprite_fs, GLES20.GL_FRAGMENT_SHADER, null);

            m_programId = GLES20.glCreateProgram();
            GLES20.glAttachShader(m_programId, vertexShaderId);
            GLES20.glAttachShader(m_programId, fragmentShaderId);
            GLES20.glLinkProgram(m_programId);

            final int [] linkStatus = new int[1];
            GLES20.glGetProgramiv(m_programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] == 0)
                throw new IOException();

            m_matrixLocation = GLES20.glGetUniformLocation(m_programId, SV_MATRIX);
            if (m_matrixLocation < 0)
                throw new IOException(SV_MATRIX);
            m_positionLocation = GLES20.glGetAttribLocation(m_programId, SV_POSITION);
            if (m_positionLocation < 0)
                throw new IOException(SV_POSITION);
            m_textureMapLocation = GLES20.glGetAttribLocation(m_programId, SV_TEXTURE_MAP);
            if (m_textureMapLocation < 0)
                throw new IOException(SV_TEXTURE_MAP);
            m_textureUnitLocation = GLES20.glGetUniformLocation(m_programId, SV_TEXTURE_UNIT);
            if (m_textureUnitLocation < 0)
                throw new IOException(SV_TEXTURE_UNIT);

            final float [] textureMap = {
                0f, 1f,
                0f, 0f,
                1f, 1f,
                1f, 0f
            };

            final int bufferCapacity = (Float.SIZE/Byte.SIZE) * textureMap.length;
            final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bufferCapacity);
            byteBuffer.order(ByteOrder.nativeOrder());
            m_textureMap = byteBuffer.asFloatBuffer();
            m_textureMap.put(textureMap);
            m_textureMap.position(0);
        }

        public void draw(float [] vpMatrix, Sprite sprite)
        {
            if (sprite.m_hasAlpha)
            {
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glEnable(GLES20.GL_BLEND);
            }

            Matrix.translateM(s_matrix, 16, s_identityMatrix, 0, sprite.x, sprite.y, sprite.z);
            Matrix.multiplyMM(s_matrix, 0, vpMatrix, 0, s_matrix, 16);

            GLES20.glUseProgram(m_programId);

            final FloatBuffer vertexData = sprite.m_vertexData;
            vertexData.position(0);
            GLES20.glVertexAttribPointer(m_positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexData);
            GLES20.glEnableVertexAttribArray(m_positionLocation);

            m_textureMap.position(0);
            GLES20.glVertexAttribPointer(m_textureMapLocation, 2, GLES20.GL_FLOAT, false, 0, m_textureMap);
            GLES20.glEnableVertexAttribArray(m_textureMapLocation);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sprite.m_textureId);

            GLES20.glUniform1i(m_textureUnitLocation, 0);

            GLES20.glUniformMatrix4fv(m_matrixLocation, 1, false, s_matrix, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glUseProgram(0);

            if (sprite.m_hasAlpha)
                GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private static class LinesDrawer
    {
        private final int m_programId;
        private final int m_matrixLocation;
        private final int m_positionLocation;
        private final int m_colorLocation;

        public LinesDrawer( Context context ) throws IOException
        {
            final int vertexShaderId = createShader(context, R.raw.solid_line_vs, GLES20.GL_VERTEX_SHADER, null);
            final int fragmentShaderId = createShader(context, R.raw.solid_line_fs, GLES20.GL_FRAGMENT_SHADER, null);

            m_programId = GLES20.glCreateProgram();
            GLES20.glAttachShader(m_programId, vertexShaderId);
            GLES20.glAttachShader(m_programId, fragmentShaderId);
            GLES20.glLinkProgram(m_programId);

            final int [] linkStatus = new int[1];
            GLES20.glGetProgramiv(m_programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] == 0)
                throw new IOException();

            m_matrixLocation = GLES20.glGetUniformLocation(m_programId, "uMatrix");
            if (m_matrixLocation < 0)
                throw new IOException();
            m_positionLocation = GLES20.glGetAttribLocation(m_programId, "vPosition");
            if (m_positionLocation < 0)
                throw new IOException();
            m_colorLocation = GLES20.glGetUniformLocation(m_programId, "uColor");
            if (m_colorLocation < 0)
                throw new IOException();
        }

        public void draw( float [] mvpMatrix, int size, int count, FloatBuffer vertexData, int color )
        {
            GLES20.glUseProgram( m_programId );

            GLES20.glUniformMatrix4fv( m_matrixLocation, 1, false, mvpMatrix, 0 );

            GLES20.glEnableVertexAttribArray( m_positionLocation );
            GLES20.glVertexAttribPointer( m_positionLocation, size, GLES20.GL_FLOAT, false, /*stride*/0, vertexData );

            final float red = ((float)Color.red(color)) / 255f;
            final float green = ((float)Color.green(color)) / 255f;
            final float blue = ((float)Color.blue(color)) / 255f;
            GLES20.glUniform4f( m_colorLocation, red, green, blue, 1.0f );

            GLES20.glDrawArrays( GLES20.GL_LINE_STRIP, 0, count );
            GLES20.glUseProgram( 0 );
        }
    }

    private static class TextDrawer
    {
        private static final int TT_ROWS = 10;
        private static final int TT_COLUMNS = 10;
        private static final float TT_SPACING = 0.2f;
        private static final char TT_START_CHAR = ' ';

        private final int m_textureHeight;
        private final int m_textureWidth;
        private final float m_spacing;
        private final float [] m_glyphMap;

        private final int m_programId;
        private final int m_matrixLocation;
        private final int m_positionLocation;
        private final int m_texCoordLocation;
        private final int m_texUnitLocation;
        private final int m_colorLocation;
        private final int m_textureId;

        private FloatBuffer m_fb;

        public TextDrawer(Context context, int textSize) throws IOException
        {
            final Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setTextSize(textSize);
            paint.setTextAlign(Paint.Align.LEFT);

            final float fontSpacing = paint.getFontSpacing();
            final int [] glyphWidth = new int[128];
            int textureWidth = 0;
            final char [] ch = new char[1];
            ch[0] = TT_START_CHAR;
            for (int row=0; row<TT_ROWS; row++)
            {
                int rowWidth = 0;
                for (int col=0; col<TT_COLUMNS; col++)
                {
                    final int gw = (int) (paint.measureText(ch, 0, 1) + 0.5);
                    glyphWidth[ch[0]] = gw;
                    rowWidth += gw;
                    if (++ch[0] == 128)
                        break;
                }
                if (rowWidth > textureWidth)
                    textureWidth = rowWidth;
                if (ch[0] == 128)
                    break;
            }
            final int spacing = (int) (((float)textureWidth)/TT_COLUMNS*TT_SPACING + 0.5);
            textureWidth += (spacing * TT_COLUMNS);

            final int rowHeight = (int) (fontSpacing + 0.5);
            m_textureHeight = (rowHeight * TT_ROWS);
            m_textureWidth = textureWidth;
            m_spacing = (((float)spacing) / textureWidth);
            m_glyphMap = new float[(TT_COLUMNS+1) * TT_ROWS];

            final Bitmap bitmap = Bitmap.createBitmap(m_textureWidth, m_textureHeight, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            int idx = 0;
            ch[0] = TT_START_CHAR;
            float y = -paint.ascent();
            loop: for (int row=0; row<TT_COLUMNS; row++)
            {
                float x = 0;
                m_glyphMap[idx++] = 0f;
                for (int col=0; col<TT_ROWS; col++)
                {
                    canvas.drawText(ch, 0, 1, x, y, paint);
                    x += (glyphWidth[ch[0]] + spacing);
                    m_glyphMap[idx++] = (x / textureWidth);
                    if (++ch[0] == 128)
                        break loop;
                }
                y += rowHeight;
            }

            /* Initialize shader program */

            final int vertexShaderId = createShader(context, R.raw.text_vs, GLES20.GL_VERTEX_SHADER, null);
            final int fragmentShaderId = createShader(context, R.raw.text_fs, GLES20.GL_FRAGMENT_SHADER, null);

            m_programId = GLES20.glCreateProgram();
            GLES20.glAttachShader(m_programId, vertexShaderId);
            GLES20.glAttachShader(m_programId, fragmentShaderId);
            GLES20.glLinkProgram(m_programId);

            final int [] linkStatus = new int[1];
            GLES20.glGetProgramiv( m_programId, GLES20.GL_LINK_STATUS, linkStatus, 0 );
            if (linkStatus[0] == 0)
                throw new IOException();

            m_matrixLocation = GLES20.glGetUniformLocation( m_programId, "u_m4Matrix" );
            if (m_matrixLocation < 0)
                throw new IOException();
            m_positionLocation = GLES20.glGetAttribLocation( m_programId, "a_v4Position" );
            if (m_positionLocation < 0)
                throw new IOException();
            m_texCoordLocation = GLES20.glGetAttribLocation( m_programId, "a_v2TexCoord" );
            if (m_texCoordLocation < 0)
                throw new IOException();
            m_texUnitLocation = GLES20.glGetUniformLocation( m_programId, "u_TexUnit" );
            if (m_texUnitLocation < 0)
                throw new IOException();
            m_colorLocation = GLES20.glGetUniformLocation( m_programId, "u_v4Color" );
            if (m_colorLocation < 0)
                throw new IOException();

            final int [] textureId = new int[1];
            GLES20.glGenTextures(1, textureId , 0);
            if (textureId [0] == 0)
                throw new IOException( "glGenTextures() failed" );
            m_textureId = textureId[0];

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, m_textureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bitmap, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            bitmap.recycle();
        }

        private static class LineInfo
        {
            public final int start;
            public final int end;
            public final float width;

            public LineInfo(int start, int end, float width)
            {
                this.start = start;
                this.end = end;
                this.width = width;
            }
        }

        public void draw(float [] matrix, String str, float x, float y, float z, float textSize,
                int color, Align align, VerticalAlign valign, float [] tmp, int tmpOffset)
        {
            final int STRIDE = (2 + 2) * (Float.SIZE / Byte.SIZE);
            final int strLength = str.length();

            int idx = 0;
            int lines = 0;
            int lineMaxLength = 0;
            int lineStartIdx = 0;

            for (;;)
            {
                if ((idx == strLength) || (str.charAt(idx) == '\n'))
                {
                    lines++;
                    final int lineLength = (idx - lineStartIdx);
                    if (lineLength > lineMaxLength)
                        lineMaxLength = lineLength;

                    if (idx == strLength)
                        break;

                    lineStartIdx = (idx + 1);
                }
                idx++;
            }

            final int capacity = Math.max(lineMaxLength, 16) * (2 * 3 * (2 + 2));
            if ((m_fb == null) || (m_fb.capacity() < capacity))
            {
                final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(Float.SIZE/Byte.SIZE * capacity);
                byteBuffer.order(ByteOrder.nativeOrder());
                m_fb = byteBuffer.asFloatBuffer();
            }

            int startIdx = 0;
            final float scale = textSize / (((float)m_textureHeight) / TT_ROWS);
            float lineMaxWidth = 0f;
            final LineInfo [] lineInfo = new LineInfo[lines];
            int lineIdx = 0;
            idx = 0;

            for (float lineWidth=0;;)
            {
                int ch;
                if ((idx == strLength) || ((ch = str.charAt(idx)) == '\n'))
                {
                    lineWidth *= (m_textureWidth * scale);
                    lineInfo[lineIdx++] = new LineInfo(startIdx, idx, lineWidth);

                    if (lineWidth > lineMaxWidth)
                        lineMaxWidth = lineWidth;

                    if (idx == strLength)
                        break;

                    idx++;
                    startIdx = idx;
                    lineWidth = 0f;
                }
                else
                {
                    if ((ch < TT_START_CHAR) || (ch > 128))
                        ch = '#';
                    ch -= TT_START_CHAR;
                    final int row = (ch / TT_COLUMNS);
                    final int col = (ch % TT_COLUMNS);
                    final int c = (row * (TT_COLUMNS + 1)) + col;
                    lineWidth += (m_glyphMap[c + 1] - m_glyphMap[c] - m_spacing);
                    idx++;
                }
            }

            if (align == Align.LEFT)
                x += (lineMaxWidth / 2f);
            else if (align == Align.RIGHT)
                x -= (lineMaxWidth / 2f);
            else if (align != Align.CENTER)
            {
                if (BuildConfig.DEBUG)
                    throw new AssertionError();
                return;
            }

            float y2 = (textSize * lines) / 2;

            if (valign == VerticalAlign.UP)
                y -= y2;
            else if (valign == VerticalAlign.DOWN)
                y += y2;
            else if (valign != VerticalAlign.CENTER)
            {
                if (BuildConfig.DEBUG)
                    throw new AssertionError();
                return;
            }

            Matrix.setIdentityM(tmp, tmpOffset+16);
            Matrix.translateM(tmp, tmpOffset+16, x, y, z);
            Matrix.multiplyMM(tmp, tmpOffset, matrix, 0, tmp, tmpOffset+16);

            for (LineInfo ll : lineInfo)
            {
                float x1;
                if (align == Align.LEFT)
                    x1 = (-lineMaxWidth / 2f);
                else if (align == Align.CENTER)
                    x1 = (-ll.width / 2f);
                else if (align == Align.RIGHT)
                    x1 = (lineMaxWidth / 2f - ll.width);
                else
                    return;

                final float y1 = y2;
                y2 -= textSize;

                m_fb.position(0);
                for (idx=ll.start; idx<ll.end; idx++)
                {
                    int ch = str.charAt(idx);
                    if ((ch < TT_START_CHAR) || (ch >= 128))
                        ch = '#';
                    ch -= TT_START_CHAR;

                    final int row = (ch / TT_COLUMNS);
                    final int col = (ch % TT_COLUMNS);
                    final int c = (row * (TT_COLUMNS+1)) + col;

                    final float tx1 = m_glyphMap[c];
                    final float tx2 = (m_glyphMap[c+1] - m_spacing);
                    final float ty1 = (((float)row) / TT_ROWS);
                    final float ty2 = (ty1 + 1f/TT_ROWS);
                    final float x2 = x1 + (m_glyphMap[c+1] - tx1 - m_spacing) * m_textureWidth * scale;

                    /* first triangle */
                    m_fb.put(x1); m_fb.put(y2);
                    m_fb.put(tx1); m_fb.put(ty2);

                    m_fb.put(x1); m_fb.put(y1);
                    m_fb.put(tx1); m_fb.put(ty1);

                    m_fb.put(x2); m_fb.put(y2);
                    m_fb.put(tx2); m_fb.put(ty2);

                    /* second triangle */
                    m_fb.put(x1); m_fb.put(y1);
                    m_fb.put(tx1); m_fb.put(ty1);

                    m_fb.put(x2); m_fb.put(y1);
                    m_fb.put(tx2); m_fb.put(ty1);

                    m_fb.put(x2); m_fb.put(y2);
                    m_fb.put(tx2); m_fb.put(ty2);

                    x1 = x2;
                }

                GLES20.glUseProgram(m_programId);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glEnable(GLES20.GL_BLEND);

                GLES20.glUniformMatrix4fv(m_matrixLocation, 1, false, tmp, tmpOffset);

                m_fb.position(0);
                GLES20.glVertexAttribPointer(m_positionLocation, 2, GLES20.GL_FLOAT, false, STRIDE, m_fb);
                GLES20.glEnableVertexAttribArray(m_positionLocation);

                m_fb.position(2);
                GLES20.glVertexAttribPointer(m_texCoordLocation, 2, GLES20.GL_FLOAT, false, STRIDE, m_fb);
                GLES20.glEnableVertexAttribArray(m_texCoordLocation);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, m_textureId);
                GLES20.glUniform1i(m_texUnitLocation, 0);

                GLES20.glUniform4f(m_colorLocation,
                        ((float)Color.red(color)) / 255f,
                        ((float)Color.green(color)) / 255f,
                        ((float)Color.blue(color)) / 255f,
                        1f);

                final int lineLength = (ll.end - ll.start);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3*2*lineLength);
            }

            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(0);
        }
    }

    public Canvas3D(Context context, int textSize) throws IOException
    {
        m_spriteDrawer = new SpriteDrawer(context);
        m_linesDrawer = new LinesDrawer(context);
        m_textDrawer = new TextDrawer(context, textSize);
    }

    public void draw(float [] vpMatrix, Sprite sprite)
    {
        m_spriteDrawer.draw(vpMatrix, sprite);
    }

    public void drawLines(float [] vpMatrix, int size, int count, FloatBuffer vertexData, int color)
    {
        m_linesDrawer.draw( vpMatrix, size, count, vertexData, color );
    }

    public void drawText(float [] matrix, String str, float x, float y, float z,
            float textSize, int color, Align align, VerticalAlign valign, float [] tmp, int tmpOffset)
    {
        m_textDrawer.draw(matrix, str, x, y, z, textSize, color, align, valign, tmp, tmpOffset);
    }
}
