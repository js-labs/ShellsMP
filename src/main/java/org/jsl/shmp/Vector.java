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

public class Vector
{
    public final float [] v;
    public final int offs;

    public Vector()
    {
        v = new float[4];
        offs = 0;
    }

    public Vector(float [] v, int offs)
    {
        this.v = v;
        this.offs = offs;
    }

    public Vector(float [] v, int offs, float x, float y, float z, float w)
    {
        this.v = v;
        this.offs = offs;
        v[offs  ] = x;
        v[offs+1] = y;
        v[offs+2] = z;
        v[offs+3] = w;
    }

    public Vector(float [] v, int offs, float x, float y, float z)
    {
        this(v, offs, x, y, z, 0f);
    }

    public Vector(float x, float y, float z, float w)
    {
        this(new float[4], 0, x, y, z, w);
    }

    public Vector(float x, float y, float z)
    {
        this(new float[4], 0, x, y, z, 0f);
    }

    public float get(int offs) { return v[this.offs + offs]; }
    public float getX() { return v[offs  ]; }
    public float getY() { return v[offs+1]; }
    public float getZ() { return v[offs+2]; }
    public float getLength() { return length(v, offs); }

    public void set(float x, float y, float z)
    {
        set(v, offs, x, y, z);
    }

    public static void set(float [] v, int offs, float x, float y, float z)
    {
        set(v, offs, x, y, z, 0f);
    }

    public static void set(float [] v, int offs, float x, float y, float z, float w)
    {
        v[offs  ] = x;
        v[offs+1] = y;
        v[offs+2] = z;
        v[offs+3] = w;
    }

    public static float getX(float [] v, int offs)
    {
        return v[offs];
    }

    public static float getY(float [] v, int offs)
    {
        return v[offs+1];
    }

    public static float getZ(float [] v, int offs)
    {
        return v[offs+2];
    }

    public static float length(float [] v, int offs)
    {
        final float x = v[offs];
        final float y = v[offs + 1];
        final float z = v[offs + 2];
        return (float) Math.sqrt(x*x + y*y + z*z);
    }

    public static float length(float x, float y)
    {
        return (float) Math.sqrt(x*x + y*y);
    }

    // 0 4  8 12
    // 1 5  9 13
    // 2 6 10 14
    // 3 7 11 15

    public static float getMVX(float [] m, int offs, float x, float y, float z, float w)
    {
        return m[offs]*x + m[offs+4]*y + m[offs+8]*z + m[offs+12]*w;
    }

    public static float getMVY(float [] m, int offs, float x, float y, float z, float w)
    {
        return m[offs+1]*x + m[offs+5]*y + m[offs+9]*z + m[offs+13]*w;
    }

    public static float getMVZ(float [] m, int offs, float x, float y, float z, float w)
    {
        return m[offs+2]*x + m[offs+6]*y + m[offs+10]*z + m[offs+14]*w;
    }

    public static float getMVW(float [] m, int offs, float x, float y, float z, float w)
    {
        return m[offs+3]*x + m[offs+7]*y + m[offs+11] + m[offs+15]*w;
    }

    public static void crossProduct(
            float [] result, int resultOffset, Vector lv, float [] rv, int rvOffset)
    {
        crossProduct(result, resultOffset, lv.v, lv.offs, rv, rvOffset);
    }

    public static void crossProduct(
            float [] result, int resultOffset, float [] lv, int lvOffset, Vector rv)
    {
        crossProduct(result, resultOffset, lv, lvOffset, rv.v, rv.offs);
    }

    public static void crossProduct(
            float [] result, int resultOffset, float [] lv, int lvOffset, float [] rv, int rvOffset)
    {
        /* ay*bz - az*by, az*bx - ax*bz, ax*by - ay*bx */
        result[resultOffset]   = lv[lvOffset+1] * rv[rvOffset+2] - lv[lvOffset+2] * rv[rvOffset+1];
        result[resultOffset+1] = lv[lvOffset+2] * rv[rvOffset]   - lv[lvOffset]   * rv[rvOffset+2];
        result[resultOffset+2] = lv[lvOffset]   * rv[rvOffset+1] - lv[lvOffset+1] * rv[rvOffset];
        result[resultOffset+3] = 0f;
    }

    public static void normalize(float [] v, int offset)
    {
        final float length = length(v, offset);
        v[offset  ] /= length;
        v[offset+1] /= length;
        v[offset+2] /= length;
    }

    public static void rotateAroundZ(float [] v, int offset, float cos, float sin)
    {
        final float x = v[offset];
        final float y = v[offset+1];
        v[offset  ] = x*cos - y*sin;
        v[offset+1] = x*sin + y*cos;
    }
}
