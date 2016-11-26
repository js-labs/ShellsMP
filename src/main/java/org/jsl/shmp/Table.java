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

public class Table
{
    private final int m_programId;
    private final int m_matrixLocation;
    private final int m_positionLocation;
    private final int m_colorLocation;
    private final int m_lightLocation;
    private final int m_eyePositionLocation;
    private final int m_meshLocation;
    private final FloatBuffer m_vertexData;
    private float m_meshX;
    private float m_meshY;

    public Table( Context context ) throws IOException
    {
        m_programId = Canvas3D.createProgram(context, R.raw.table_vs, R.raw.table_fs);

        m_matrixLocation = GLES20.glGetUniformLocation(m_programId, "uMatrix");
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

        m_meshLocation = GLES20.glGetUniformLocation(m_programId, "u_v2Mesh");
        if (m_meshLocation < 0)
            throw new IOException();

        final int bufferCapacity = (Float.SIZE / Byte.SIZE) * 12;
        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect( bufferCapacity );
        byteBuffer.order( ByteOrder.nativeOrder() );
        m_vertexData = byteBuffer.asFloatBuffer();
    }

    public void setSize( float width, float height )
    {
        final float [] vertices =
        {
            -width/2,  height/2, 0f,
            -width/2, -height/2, 0f,
             width/2,  height/2, 0f,
             width/2, -height/2, 0f
        };
        m_vertexData.put( vertices );
        m_meshX = (width / 10);
        m_meshY = (height / 10);
    }

    public void draw( float [] mvpMatrix, float [] light, float [] eyePosition )
    {
        final int color = Color.GRAY;

        GLES20.glUseProgram(m_programId);

        GLES20.glUniformMatrix4fv(m_matrixLocation, 1, false, mvpMatrix, 0);

        m_vertexData.position(0);
        GLES20.glVertexAttribPointer(m_positionLocation, 3, GLES20.GL_FLOAT, false, 0, m_vertexData);
        GLES20.glEnableVertexAttribArray(m_positionLocation);

        final float red = ((float) Color.red(color)) / 255f;
        final float green = ((float)Color.green(color)) / 255f;
        final float blue = ((float)Color.blue(color)) / 255f;
        GLES20.glUniform3f(m_colorLocation, red, green, blue);

        GLES20.glUniform3fv(m_lightLocation, 6, light, 0);
        GLES20.glUniform3f(m_eyePositionLocation, eyePosition[0], eyePosition[1], eyePosition[2]);
        GLES20.glUniform2f(m_meshLocation, m_meshX, m_meshY);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glUseProgram(0);
    }
}
