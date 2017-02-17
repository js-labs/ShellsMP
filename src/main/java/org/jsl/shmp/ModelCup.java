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
import android.graphics.Color;
import android.opengl.GLES20;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Set;

class ModelCup
{
    private static final int STRIDE = (3 + 3) * (Float.SIZE / Byte.SIZE);

    private static final String SV_MATRIX = "u_m4Matrix";
    private static final String SV_EYE_POSITION = "u_v3EyePosition";
    private static final String SV_LIGHT_POSITION = "u_v3Light";
    private static final String SV_COLOR = "u_v3Color";
    private static final String SV_SHADOW_MATRIX = "u_m4ShadowMatrix";
    private static final String SV_SHADOW_TEXTURE = "u_shadowTexture";
    private static final String SV_POSITION = "a_v4Position";

    private class Shadow
    {
        private final int m_programId;
        private final int m_matrixLocation;
        private final int m_positionLocation;

        public Shadow(Context context) throws IOException
        {
            m_programId = Canvas3D.createProgram(context, R.raw.shadow_vs, R.raw.shadow_fs, null);

            m_matrixLocation = GLES20.glGetUniformLocation(m_programId, SV_MATRIX);
            if (m_matrixLocation < 0)
                throw new IOException(SV_MATRIX);

            m_positionLocation = GLES20.glGetAttribLocation(m_programId, SV_POSITION);
            if (m_positionLocation < 0)
                throw new IOException(SV_POSITION);
        }

        public void draw(float [] mvpMatrix, int mvpMatrixOffset)
        {
            GLES20.glUseProgram(m_programId);

            GLES20.glUniformMatrix4fv(m_matrixLocation, 1, false, mvpMatrix, mvpMatrixOffset);

            /* Side */
            m_vertexData.position(0);
            GLES20.glEnableVertexAttribArray(m_positionLocation);
            GLES20.glVertexAttribPointer(m_positionLocation, 3, GLES20.GL_FLOAT, false, STRIDE, m_vertexData);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, m_stripes*2+2);

            /* Bottom */
            m_vertexData.position(m_bottomOffs);
            GLES20.glEnableVertexAttribArray(m_positionLocation);
            GLES20.glVertexAttribPointer(m_positionLocation, 3, GLES20.GL_FLOAT, false, STRIDE, m_vertexData);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, m_stripes+2);

            GLES20.glUseProgram(0);
        }
    }

    private final int m_programId;
    private final int m_matrixLocation;
    private final int m_eyePositionLocation;
    private final int m_lightPositionLocation;
    private final int m_colorLocation;
    private final int m_shadowMatrixLocation;
    private final int m_shadowTextureLocation;
    private final int m_positionLocation;
    private final int m_normalLocation;
    private final FloatBuffer m_vertexData;
    private final int m_stripes;
    private final int m_bottomOffs;
    private final Shadow m_shadow;

    public ModelCup(Context context, int stripes, Set<String> macro) throws IOException
    {
        m_programId = Canvas3D.createProgram(context, R.raw.cup_vs, R.raw.cup_fs, macro);

        m_matrixLocation = GLES20.glGetUniformLocation(m_programId, SV_MATRIX);
        if (m_matrixLocation < 0)
            throw new IOException(SV_MATRIX);

        m_eyePositionLocation = GLES20.glGetUniformLocation(m_programId, SV_EYE_POSITION);
        if (m_eyePositionLocation < 0)
            throw new IOException(SV_EYE_POSITION);

        m_lightPositionLocation = GLES20.glGetUniformLocation(m_programId, SV_LIGHT_POSITION);
        if (m_lightPositionLocation < 0)
            throw new IOException(SV_LIGHT_POSITION);

        m_colorLocation = GLES20.glGetUniformLocation(m_programId, SV_COLOR);
        if (m_colorLocation < 0)
            throw new IOException(SV_COLOR);

        if ((macro != null) && macro.contains("RENDER_SHADOWS"))
        {
            m_shadowMatrixLocation = GLES20.glGetUniformLocation(m_programId, SV_SHADOW_MATRIX);
            if (m_shadowMatrixLocation < 0)
                throw new IOException(SV_SHADOW_MATRIX);

            m_shadowTextureLocation = GLES20.glGetUniformLocation(m_programId, SV_SHADOW_TEXTURE);
            if (m_shadowMatrixLocation < 0)
                throw new IOException(SV_SHADOW_TEXTURE);
        }
        else
        {
            m_shadowMatrixLocation = -1;
            m_shadowTextureLocation = -1;
        }

        m_positionLocation = GLES20.glGetAttribLocation(m_programId, SV_POSITION);
        if (m_positionLocation < 0)
            throw new IOException(SV_POSITION);

        m_normalLocation = GLES20.glGetAttribLocation(m_programId, "a_v3Normal");
        if (m_normalLocation < 0)
            throw new IOException();

        m_shadow = new Shadow(context);

        m_stripes = stripes;
        m_bottomOffs = ((stripes + 1) * 2 * 6);
        final int bufferCapacity = (Float.SIZE / Byte.SIZE) * (m_bottomOffs + /*bottom*/(stripes+2)*6);
        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect( bufferCapacity );
        byteBuffer.order( ByteOrder.nativeOrder() );
        m_vertexData = byteBuffer.asFloatBuffer();

        final float neckRadius = 1f;
        final float [] tmp = new float[8];
        final float height =  (neckRadius / 6f * 10f);
        final float bottomRadius = (neckRadius / 6f * 4.5f);

        final float pitch = (float) (-Math.PI * 2f / stripes);
        final float pitchCos = (float) Math.cos( pitch );
        final float pitchSin = (float) Math.sin( pitch );

        final float [] neck = { (float)(neckRadius * Math.cos(pitch/2f)), (float)(neckRadius * Math.sin(pitch/2f)), 0f, 0f };
        final float [] bottom = { bottomRadius, 0f, height, 0f };
        final float [] n1 = new float[4];
        final float [] n2 = new float[4];
        final float [] bottomNormal = { 0f, 0f, 1f }; /* bottom normal */

        /*  0 p1 -  3 p1n
         *  6 p2 -  9 p2n
         * 12 p3 - 15 p3n
         */
        m_vertexData.position(0);
        m_vertexData.put(bottom, 0, 3);

        m_vertexData.position(6);
        m_vertexData.put(neck, 0, 3);

        m_vertexData.position(m_bottomOffs);
        m_vertexData.put(/*x*/0f);
        m_vertexData.put(/*y*/0f);
        m_vertexData.put(/*z*/height);
        m_vertexData.put(bottomNormal);
        m_vertexData.put(bottom, 0, 3);
        m_vertexData.put(bottomNormal);

        Vector.rotateAroundZ(bottom, 0, pitchCos, pitchSin);
        Vector.rotateAroundZ(neck, 0, pitchCos, pitchSin);

        m_vertexData.position(12);
        m_vertexData.put(bottom, 0, 3);

        m_vertexData.position(18);
        m_vertexData.put(neck, 0, 3);

        m_vertexData.position(m_bottomOffs + 6*2);
        m_vertexData.put(bottom, 0, 3);
        m_vertexData.put(bottomNormal);
        int bottomPos = m_vertexData.position();

        Vector.rotateAroundZ(bottom, 0, pitchCos, pitchSin);
        Vector.rotateAroundZ(neck, 0, pitchCos, pitchSin);

        /* Lighting requires face normal,
         * we could calculate it once for one face and then rotate.
         */
        Vector.set(tmp, 0,
                m_vertexData.get(0) - m_vertexData.get(6),
                m_vertexData.get(1) - m_vertexData.get(7),
                m_vertexData.get(2) - m_vertexData.get(8));

        Vector.set(tmp, 4,
                m_vertexData.get(12) - m_vertexData.get(6),
                m_vertexData.get(13) - m_vertexData.get(7),
                m_vertexData.get(14) - m_vertexData.get(8));

        Vector.crossProduct(n1, 0, tmp, 0, tmp, 4);
        Vector.normalize(n1, 0);

        Vector.set(tmp, 0,
                m_vertexData.get(18) - m_vertexData.get(12),
                m_vertexData.get(19) - m_vertexData.get(13),
                m_vertexData.get(20) - m_vertexData.get(14));

        Vector.set(tmp, 4,
                m_vertexData.get(6) - m_vertexData.get(12),
                m_vertexData.get(7) - m_vertexData.get(13),
                m_vertexData.get(8) - m_vertexData.get(14));

        Vector.crossProduct(n2, 0, tmp, 0, tmp, 4);
        Vector.normalize(n2, 0);

        m_vertexData.position(3);
        m_vertexData.put(n1, 0, 3);
        m_vertexData.position(9);
        m_vertexData.put(n2, 0, 3);

        Vector.rotateAroundZ(n1, 0, pitchCos, pitchSin);
        Vector.rotateAroundZ(n2, 0, pitchCos, pitchSin);

        m_vertexData.position(15);
        m_vertexData.put(n1, 0, 3);
        m_vertexData.position(21);
        m_vertexData.put(n2, 0, 3);

        Vector.rotateAroundZ(n1, 0, pitchCos, pitchSin);
        Vector.rotateAroundZ(n2, 0, pitchCos, pitchSin);

        for (int idx=stripes-1; idx>0; idx--)
        {
            m_vertexData.put(bottom, 0, 3);
            m_vertexData.put(n1, 0, 3);
            m_vertexData.put(neck, 0, 3);
            m_vertexData.put(n2, 0, 3);

            final int pos = m_vertexData.position();
            m_vertexData.position(bottomPos);
            m_vertexData.put(bottom, 0, 3);
            m_vertexData.put(bottomNormal);
            bottomPos = m_vertexData.position();
            m_vertexData.position(pos);

            Vector.rotateAroundZ(bottom, 0, pitchCos, pitchSin);
            Vector.rotateAroundZ(n1, 0, pitchCos, pitchSin);
            Vector.rotateAroundZ(neck, 0, pitchCos, pitchSin);
            Vector.rotateAroundZ(n2, 0, pitchCos, pitchSin);
        }
    }

    public void draw(
            float [] mvpMatrix, int mvpMatrixOffset,
            float [] eyePosition, int eyePositionOffset,
            float [] light, int lightOffset,
            float [] shadowMatrix, int shadowMatrixOffset, int shadowTextureId)
    {
        final int color = Color.BLUE;

        GLES20.glUseProgram(m_programId);

        GLES20.glUniformMatrix4fv(m_matrixLocation, 1, false, mvpMatrix, mvpMatrixOffset);
        GLES20.glUniform3fv(m_eyePositionLocation, 1, eyePosition, eyePositionOffset);
        GLES20.glUniform3fv(m_lightPositionLocation, 2, light, lightOffset);

        if (shadowMatrix == null)
        {
            if (BuildConfig.DEBUG && (m_shadowMatrixLocation > 0))
                throw new AssertionError();
        }
        else
        {
            if (BuildConfig.DEBUG && (m_shadowMatrixLocation < 0))
                throw new AssertionError();

            GLES20.glUniformMatrix4fv(m_shadowMatrixLocation, 1, false, shadowMatrix, shadowMatrixOffset);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTextureId);
            GLES20.glUniform1i(m_shadowTextureLocation, 0);
        }

        final float red = ((float) Color.red(color)) / 255f;
        final float green = ((float)Color.green(color)) / 255f;
        final float blue = ((float)Color.blue(color)) / 255f;
        GLES20.glUniform3f(m_colorLocation, red, green, blue);

        /* Draw side */

        m_vertexData.position(0);
        GLES20.glVertexAttribPointer( m_positionLocation, 3, GLES20.GL_FLOAT, false, STRIDE, m_vertexData );
        GLES20.glEnableVertexAttribArray( m_positionLocation );

        m_vertexData.position(3);
        GLES20.glVertexAttribPointer( m_normalLocation, 3, GLES20.GL_FLOAT, false, STRIDE, m_vertexData );
        GLES20.glEnableVertexAttribArray( m_normalLocation );

        GLES20.glDrawArrays( GLES20.GL_TRIANGLE_STRIP, 0, m_stripes*2+2 );

        /* Draw bottom */

        m_vertexData.position( m_bottomOffs );
        GLES20.glVertexAttribPointer( m_positionLocation, 3, GLES20.GL_FLOAT, false, STRIDE, m_vertexData );
        GLES20.glEnableVertexAttribArray( m_positionLocation );

        m_vertexData.position( m_bottomOffs + 3 );
        GLES20.glVertexAttribPointer( m_normalLocation, 3, GLES20.GL_FLOAT, false, STRIDE, m_vertexData );
        GLES20.glEnableVertexAttribArray( m_normalLocation );

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, m_stripes+2);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
    }

    public void drawShadow(float [] mvpMatrix, int mvpMatrixOffset)
    {
        m_shadow.draw(mvpMatrix, mvpMatrixOffset);
    }
}
