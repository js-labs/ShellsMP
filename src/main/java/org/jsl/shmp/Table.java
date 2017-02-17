/*
 * Copyright (C) 2015 Sergey Zubarev, info@js-labs.org
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

public class Table
{
    private final int m_programId;
    private final int m_matrixLocation;
    private final int m_positionLocation;
    private final int m_colorLocation;
    private final int m_lightLocation;
    private final int m_eyePositionLocation;
    private final int m_shadowMatrixLocation;
    private final int m_shadowTextureLocation;
    private final int m_meshLocation;
    private final FloatBuffer m_vertexData;
    private float m_meshX;
    private float m_meshY;

    public Table(Context context, Set<String> macro) throws IOException
    {
        m_programId = Canvas3D.createProgram(context, R.raw.table_vs, R.raw.table_fs, macro);

        m_matrixLocation = GLES20.glGetUniformLocation(m_programId, "u_m4Matrix");
        if (m_matrixLocation < 0)
            throw new IOException();

        m_positionLocation = GLES20.glGetAttribLocation(m_programId, "a_v4Position");
        if (m_positionLocation < 0)
            throw new IOException();

        m_colorLocation = GLES20.glGetUniformLocation(m_programId, "u_v3Color");
        if (m_colorLocation < 0)
            throw new IOException();

        m_lightLocation = GLES20.glGetUniformLocation(m_programId, "u_v3Light");
        if (m_lightLocation < 0)
            throw new IOException();

        m_eyePositionLocation = GLES20.glGetUniformLocation(m_programId, "u_v3EyePosition");
        if (m_eyePositionLocation < 0)
            throw new IOException();

        if ((macro != null) && macro.contains("RENDER_SHADOWS"))
        {
            m_shadowMatrixLocation = GLES20.glGetUniformLocation(m_programId, "u_m4ShadowMatrix");
            if (m_shadowMatrixLocation < 0)
                throw new IOException();

            m_shadowTextureLocation = GLES20.glGetUniformLocation(m_programId, "u_shadowTexture");
            if (m_shadowMatrixLocation < 0)
                throw new IOException();
        }
        else
        {
            m_shadowMatrixLocation = -1;
            m_shadowTextureLocation = -1;
        }

        m_meshLocation = GLES20.glGetUniformLocation(m_programId, "u_v2Mesh");
        if (m_meshLocation < 0)
            throw new IOException();

        final int bufferCapacity = (Float.SIZE / Byte.SIZE) * 8;
        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect( bufferCapacity );
        byteBuffer.order(ByteOrder.nativeOrder());
        m_vertexData = byteBuffer.asFloatBuffer();
    }

    public void setSize(float width, float height)
    {
        final float [] vertices =
        {
            -width/2, -height/2,
            -width/2,  height/2,
             width/2, -height/2,
             width/2,  height/2
        };

        m_vertexData.put(vertices);
        m_vertexData.position(0);

        m_meshX = (width / 10);
        m_meshY = (height / 10);
    }

    public void draw(float [] vpMatrix, Vector eyePosition, Vector light, ShadowObject shadowObject, float [] tmp, int tmpOffset)
    {
        GLES20.glUseProgram(m_programId);

        GLES20.glUniformMatrix4fv(m_matrixLocation, 1, false, vpMatrix, 0);

        GLES20.glVertexAttribPointer(m_positionLocation, 2, GLES20.GL_FLOAT, false, 0, m_vertexData);
        GLES20.glEnableVertexAttribArray(m_positionLocation);

        final int color = Color.GRAY;
        final float red = ((float) Color.red(color)) / 255f;
        final float green = ((float)Color.green(color)) / 255f;
        final float blue = ((float)Color.blue(color)) / 255f;
        GLES20.glUniform3f(m_colorLocation, red, green, blue);

        tmp[tmpOffset] = light.getX();
        tmp[tmpOffset+1] = light.getY();
        tmp[tmpOffset+2] = light.getZ();
        tmp[tmpOffset+3] = light.get(4);
        tmp[tmpOffset+4] = light.get(5);
        tmp[tmpOffset+5] = light.get(6);
        GLES20.glUniform3fv(m_lightLocation, 2, tmp, tmpOffset);
        GLES20.glUniform3fv(m_eyePositionLocation, 1, eyePosition.v, eyePosition.offs);

        if (shadowObject == null)
        {
            if (BuildConfig.DEBUG && (m_shadowMatrixLocation > 0))
                throw new AssertionError();
        }
        else
        {
            if (BuildConfig.DEBUG && (m_shadowMatrixLocation < 0))
                throw new AssertionError();

            GLES20.glUniformMatrix4fv(m_shadowMatrixLocation, 1, false, shadowObject.matrix, 16);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowObject.textureId);
            GLES20.glUniform1i(m_shadowTextureLocation, 0);
        }

        GLES20.glUniform2f(m_meshLocation, m_meshX, m_meshY);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
    }
}
