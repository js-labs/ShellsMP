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
package org.jsl.prtgen;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class Main
{
    private static final Logger s_logger = Logger.getLogger( "prtgen" );

    private static final int VS_DEFAULT = 0;
    private static final int VS_FILE    = 1;
    private static final String INDENT1 = "    ";
    private static final String INDENT2 = (INDENT1 + INDENT1);
    private static final String INDENT3 = (INDENT2 + INDENT1);

    private static final Type s_typeInt = new Type("int", "int", "Integer", "Int");
    private static final Type s_typeShort = new Type("short", "short", "Short", "Short");
    private static final Type s_typeFloat = new Type("float", "float", "Float", "Float");
    private static final Type s_typeBoolean = new Type("boolean", "Boolean", "Byte", "");
    private static final Type s_typeString = new Type("string", "String", "String", "");

    private static final IntegerCoder s_integerCoder = new IntegerCoder();
    private static final StringCoder s_stringCoder = new StringCoder();
    private static final TypeCoder s_typeCoder = new TypeCoder();

    private static class TypeFormatException extends IllegalArgumentException
    {
        public TypeFormatException(String msg)
        {
            super(msg);
        }
    }

    private static abstract class Attribute
    {
        private final String m_name;
        private final boolean m_mandatory;
        protected int m_valueSource;

        public Attribute(String name, boolean mandatory)
        {
            m_name = name;
            m_mandatory = mandatory;
            m_valueSource = VS_DEFAULT;
        }

        public String getName() { return m_name; }
        public boolean isMandatory() { return m_mandatory; }
        public int getValueSource() { return m_valueSource; }

        abstract void setValue(String value) throws IllegalArgumentException;
    }

    private interface CoderT<T>
    {
        T decode(String str);
    }
    
    private static class IntegerCoder implements CoderT<Integer>
    {
        public Integer decode(String str)
        {
            return Integer.decode(str);
        }
    }
    
    private static class StringCoder implements CoderT<String>
    {
        public String decode(String str)
        {
            return str;
        }
    }

    private static class TypeCoder implements CoderT<Type>
    {
        private HashMap<String, Type> m_types;

        public TypeCoder()
        {
            m_types = new HashMap<String, Type>();
            m_types.put(s_typeInt.getName(), s_typeInt);
            m_types.put(s_typeShort.getName(), s_typeShort);
            m_types.put(s_typeFloat.getName(), s_typeFloat);
            m_types.put(s_typeBoolean.getName(), s_typeBoolean);
            m_types.put(s_typeString.getName(), s_typeString);
        }

        public Type decode(String str)
        {
            final Type fieldType = m_types.get(str);
            if (fieldType == null)
                throw new TypeFormatException("Unknown type '" + str + "'");
            return m_types.get(str);
        }
    }

    private static class AttributeT<T> extends Attribute
    {
        private final CoderT<T> m_coder;
        private T m_value;

        public AttributeT(String name, boolean mandatory, CoderT<T> coder, T value)
        {
            super(name, mandatory);
            m_coder = coder;
            m_value = value;
        }

        public T getValue()
        {
            return m_value;
        }

        void setValue(String str)
        {
            m_value = m_coder.decode(str);
            m_valueSource = VS_FILE;
        }
    }

    private static class Type
    {
        private final String m_name;
        private final String m_javaType;
        private final String m_javaSizeType;
        private final String m_suffix;

        public Type(String name, String javaType, String javaSizeType, String suffix)
        {
            m_name = name;
            m_javaType = javaType;
            m_javaSizeType = javaSizeType;
            m_suffix = suffix;
        }

        public String getName() { return m_name; }
        public String getJavaType() { return m_javaType; }
        public String getJavaSizeType() { return m_javaSizeType; }
        public String getPutSuffix() { return m_suffix; }
        public String getGetSuffix() { return m_suffix; }
    }

    private static abstract class Section
    {
        private final String m_name;

        public Section(String name)
        {
            m_name = name;
        }

        public String getSectionName()
        {
            return m_name;
        }

        protected static void processElement(XMLStreamReader xmlSR, String sectionName, Attribute [] attributes, Section [] sections )
            throws XMLStreamException, IllegalArgumentException
        {
            final TreeMap<String, Attribute> attrs = new TreeMap<String, Attribute>();
            final TreeMap<String, Section> sects = new TreeMap<String, Section>();

            if (attributes != null)
            {
                for (Attribute a : attributes)
                    attrs.put(a.getName(), a);
            }

            if (sections != null)
            {
                for (Section s : sections)
                    sects.put(s.getSectionName(), s);
            }

            /* Process attributes */

            for (int idx=0; idx<xmlSR.getAttributeCount(); idx++)
            {
                final QName name = xmlSR.getAttributeName(idx);
                final String str = name.toString();
                final Attribute a = attrs.get(str);
                if (a == null)
                    throw new IllegalArgumentException("Unexpected attribute: '" + str + "'");
                else
                    a.setValue(xmlSR.getAttributeValue(idx));
            }

            if (attributes != null)
            {
                for (Attribute a : attributes)
                {
                    if (a.isMandatory() && (a.getValueSource() == VS_DEFAULT))
                        throw new IllegalArgumentException("Mandatory attribute '" + a.getName() + "' is not set");
                }
            }
        
            /* Process sections */

            while (xmlSR.hasNext())
            {
                final int eventType = xmlSR.next();
                if (eventType == XMLStreamConstants.START_ELEMENT)
                {
                    final String elementName = xmlSR.getName().toString();
                    if (s_logger.isLoggable(Level.FINE))
                        s_logger.log(Level.FINE, "START_ELEMENT: " + elementName);

                    final Section section = sects.get(elementName);
                    if (section == null)
                        throw new IllegalArgumentException("Unexpected element '" + elementName + "'");
                    else
                        section.processElement(xmlSR);
                }
                else if (eventType == XMLStreamConstants.END_ELEMENT)
                {
                    final String elementName = xmlSR.getName().toString();
                    if (s_logger.isLoggable(Level.FINE))
                        s_logger.log(Level.FINE, "END_ELEMENT: " + elementName);
                    if (!sectionName.equals(elementName))
                        throw new IllegalArgumentException("Unexpected element end '" + elementName + "'");
                    break;
                }
            }
        }

        public abstract String processElement(XMLStreamReader xmlSR) throws XMLStreamException, IllegalArgumentException;
    }

    private static class MultipleSectionT<T> extends Section
    {
        private final Class m_class;
        private final TreeMap<String, T> m_sections;

        public MultipleSectionT(String name, Class clz)
        {
            super(name);
            m_class = clz;
            m_sections = new TreeMap<String, T>();
        }
        
        public Collection<T> getSections()
        {
            return m_sections.values();
        }
        
        public String processElement(XMLStreamReader xmlSR) throws XMLStreamException, IllegalArgumentException
        {
            try
            {
                final Constructor constructor = m_class.getConstructor();
                final T section = (T) constructor.newInstance();
                final String key = ((Section)section).processElement(xmlSR);
                if (m_sections.get(key) != null)
                    throw new IllegalArgumentException("Duplicate instance '" + key + "'");
                m_sections.put(key, section);
            }
            catch (final NoSuchMethodException|InstantiationException|IllegalAccessException|InvocationTargetException ex)
            {
                throw new IllegalArgumentException(ex.toString());
            }
            return null;
        }
    }

    private static class Fields extends Section
    {
        private final ArrayList<Field> m_fields;
        private final HashMap<String, Field> m_fieldsByName;

        public Fields()
        {
            super("field");
            m_fields = new ArrayList<>();
            m_fieldsByName = new HashMap<String, Field>();
        }

        public Collection<Field> getFields()
        {
            return m_fields;
        }

        public String processElement(XMLStreamReader xmlSR) throws XMLStreamException, IllegalArgumentException
        {
            final Field field = new Field();
            final String key = field.processElement(xmlSR);
            if (m_fieldsByName.get(key) != null)
                throw new IllegalArgumentException("Duplicate field '" + key + "'");
            m_fieldsByName.put(key, field);
            m_fields.add(field);
            return null;
        }
    }

    private static class Field extends Section
    {
        private final AttributeT<String> m_name;
        private final AttributeT<Type> m_type;

        public Field()
        {
            super("field");
            m_name = new AttributeT<String>("name", /*mandatory*/true, s_stringCoder, null);
            m_type = new AttributeT<Type>("type", /*mandatory*/true, s_typeCoder, null);
        }

        public String processElement(XMLStreamReader xmlSR) throws XMLStreamException, IllegalArgumentException
        {
            final Attribute [] attributes = new Attribute [] {m_name, m_type};
            processElement(xmlSR, getSectionName(), attributes, null);
            return getFieldName();
        }

        public String getFieldName() { return m_name.getValue(); }
        public Type getFieldType() { return m_type.getValue(); }
    }

    private static class Message extends Section
    {
        private final AttributeT<Integer> m_id;
        private final AttributeT<String> m_name;
        private final Fields m_fields;

        private static String getClassName(String messageName)
        {
            final String [] parts = messageName.split(" ");
            String ret = "";
            for (String s : parts)
            {
                ret += s.substring(0, 1).toUpperCase();
                ret += s.substring(1, s.length());
            }
            return ret;
        }

        private static String getFieldNameSL(String fieldName)
        {
            final String [] parts = fieldName.split(" ");
            String ret = parts[0];
            for (int idx=1; idx<parts.length; idx++)
            {
                final String s = parts[idx];
                ret += s.substring(0, 1).toUpperCase();
                ret += s.substring(1, s.length());
            }
            return ret;
        }

        private static String getFieldNameCL(String fieldName)
        {
            final String [] parts = fieldName.split(" ");
            String ret = "";
            for (String s : parts)
            {
                ret += s.substring(0, 1).toUpperCase();
                ret += s.substring(1, s.length());
            }
            return ret;
        }

        public Message()
        {
            super("message");
            m_id = new AttributeT<Integer>("id", /*mandatory*/true, s_integerCoder, null);
            m_name = new AttributeT<String>("name", /*mandatory*/true, s_stringCoder, null);
            m_fields = new Fields();
        }

        public String processElement(XMLStreamReader xmlSR) throws XMLStreamException, IllegalArgumentException
        {
            final Attribute [] attributes = new Attribute [] {m_id, m_name};
            final Section [] sections = new Section [] {m_fields};
            processElement(xmlSR, getSectionName(), attributes, sections);
            return m_id.getValue().toString();
        }

        public Collection<Field> getFields()
        {
            return m_fields.getFields();
        }

        public void dump(StringBuilder sb, Type messageIdType, Type stringSizeType)
        {
            final Collection<Field> fields = getFields();
            final String messageName = m_name.getValue();
            sb.append(INDENT1 + "public static class " + getClassName(messageName) + " extends Message\n");
            sb.append(INDENT1 + "{\n");
            String constructorArguments = "";
            String init = "";
            String putFields = "";
            String stringsPut1 = "";
            String stringsPut2 = "";
            String extSize = "";
            int stringFields = 0;

            if (!fields.isEmpty())
            {
                sb.append(INDENT2 + "/*");
                for (Field f : fields)
                {
                    final Type fieldType = f.getFieldType();
                    final String fieldName = getFieldNameSL(f.getFieldName());
                    sb.append(" " + fieldType.getName() + " : " + f.getFieldName() + "\n");
                    sb.append(INDENT2 + " *");

                    if (f.getFieldType() == s_typeString)
                    {
                        stringFields++;
                        stringsPut1 += INDENT3 + "msg.put" + stringSizeType.getPutSuffix() + "((" + stringSizeType.getName() + ")" + fieldName + "Bytes.length);\n";
                        stringsPut2 += INDENT3 + "msg.put(" + fieldName + "Bytes);\n";
                    }
                    else
                    {
                        if (!extSize.isEmpty())
                            extSize += " + ";
                        extSize += "(" + fieldType.getJavaSizeType() + ".SIZE/Byte.SIZE)";
                        if (f.getFieldType() == s_typeBoolean)
                            putFields += INDENT3 + "msg.put((byte)(" + fieldName + "?1:0));\n";
                        else
                            putFields += INDENT3 + "msg.put" + fieldType.getPutSuffix() + "(" + fieldName + ");\n";
                    }

                    if (!constructorArguments.isEmpty())
                        constructorArguments += ", ";
                    constructorArguments += fieldType.getJavaType();
                    constructorArguments += " ";
                    constructorArguments += fieldName;
                }
                sb.append("/\n");
            }
            sb.append(INDENT2 + "public static final " + messageIdType.getJavaType() + " ID = " + m_id.getValue() + ";\n\n");

            if (stringFields > 0)
            {
                for (Field f : fields)
                {
                    final Type fieldType = f.getFieldType();
                    if (fieldType == s_typeString)
                    {
                        final String fieldName = getFieldNameSL(f.getFieldName());
                        init += INDENT3 + "final byte [] " + fieldName + "Bytes = " + fieldName + ".getBytes(CHARSET);\n";
                        if (!extSize.isEmpty())
                            extSize += " + ";
                        extSize += "(" + stringSizeType.getJavaSizeType() + ".SIZE/Byte.SIZE) + ";
                        extSize += fieldName + "Bytes.length";
                    }
                }
            }

            if (fields.isEmpty())
                init += INDENT3 + "final int extSize = 0;\n";
            else
                init += INDENT3 + "final int extSize = " + extSize + ";\n";

            sb.append(INDENT2 + "public static RetainableByteBuffer create(RetainableByteBufferPool pool");
            if (!constructorArguments.isEmpty())
            {
                sb.append(", ");
                sb.append(constructorArguments);
            }
            sb.append(")\n");
            sb.append(INDENT2 + "{\n");
            sb.append(init);
            sb.append(INDENT2 + "    final RetainableByteBuffer msg = Message.create(pool, ID, extSize);\n");
            sb.append(putFields);
            sb.append(stringsPut1);
            sb.append(stringsPut2);
            sb.append(INDENT2 + "    msg.rewind();\n");
            sb.append(INDENT2 + "    return msg;\n");
            sb.append(INDENT2 + "}\n\n");

            sb.append(INDENT2 + "public static ByteBuffer create(" + constructorArguments + ")\n");
            sb.append(INDENT2 + "{\n");
            sb.append(init);
            sb.append(INDENT2 + "    final ByteBuffer msg = Message.create(ID, extSize);\n");
            sb.append(putFields);
            sb.append(stringsPut1);
            sb.append(stringsPut2);
            sb.append(INDENT2 + "    msg.rewind();\n");
            sb.append(INDENT2 + "    return msg;\n");
            sb.append(INDENT2 + "}\n");

            String offs = "";
            if (!fields.isEmpty())
            {
                for (Field f : fields)
                {
                    final Type fieldType = f.getFieldType();
                    if (fieldType != s_typeString)
                    {
                        sb.append("\n");
                        sb.append(INDENT2 + "public static " + fieldType.getName() + " get" + getFieldNameCL(f.getFieldName()) + "(RetainableByteBuffer msg)\n");
                        sb.append(INDENT2 + "{\n");
                        sb.append(INDENT2 + "    final int pos = (msg.position() + HEADER_SIZE" + offs + ");\n");
                        if (fieldType == s_typeBoolean)
                        {
                            sb.append(INDENT2 + "    final byte b = msg.get(pos);\n");
                            sb.append(INDENT2 + "    return (b != 0);\n");
                        }
                        else
                            sb.append(INDENT2 + "    return msg.get" + fieldType.getGetSuffix() + "(pos);\n");
                        sb.append(INDENT2 + "}\n");
                        offs += " + ";
                        offs += fieldType.getJavaSizeType() + ".SIZE/Byte.SIZE";
                    }
                }

                int idx = 0;
                for (Field f : fields)
                {
                    final Type fieldType = f.getFieldType();
                    if (fieldType == s_typeString)
                    {
                        sb.append("\n");
                        sb.append(INDENT2 + "static String get" + getFieldNameCL(f.getFieldName()) + "(RetainableByteBuffer msg)\n");
                        sb.append(INDENT2 + "{\n");
                        sb.append(INDENT2 + "    final int pos = msg.position();\n");
                        sb.append(INDENT2 + "    try\n");
                        sb.append(INDENT2 + "    {\n");
                        sb.append(INDENT2 + "        final int lengthTablePos = (pos + HEADER_SIZE" + offs + ");\n");
                        sb.append(INDENT2 + "        msg.position(lengthTablePos);\n");
                        sb.append(INDENT2 + "        int offs = 0;\n");
                        if (idx > 0)
                        {
                            sb.append(INDENT2 + "        for (int idx=0; idx<" + idx + "; idx++)\n");
                            sb.append(INDENT2 + "            offs += msg.get" + stringSizeType.getGetSuffix() + "();\n");
                        }
                        sb.append(INDENT2 + "        final int length = msg.get" + stringSizeType.getGetSuffix() + "();\n");
                        sb.append(INDENT2 + "        if (length > 0)\n");
                        sb.append(INDENT2 + "        {\n");
                        sb.append(INDENT2 + "            msg.position(lengthTablePos + (" + stringSizeType.getJavaSizeType() + ".SIZE/Byte.SIZE)*" + stringFields + " + offs);\n");
                        sb.append(INDENT2 + "            final byte [] bytes = new byte[length];\n");
                        sb.append(INDENT2 + "            msg.get(bytes, 0, length);\n");
                        sb.append(INDENT2 + "            return new String(bytes, CHARSET);\n");
                        sb.append(INDENT2 + "        }\n");
                        sb.append(INDENT2 + "    }\n");
                        sb.append(INDENT2 + "    finally\n");
                        sb.append(INDENT2 + "    {\n");
                        sb.append(INDENT2 + "        msg.position(pos);\n");
                        sb.append(INDENT2 + "    }\n");
                        sb.append(INDENT2 + "    return null;\n");
                        sb.append(INDENT2 + "}\n");
                        idx++;
                    }
                }
            }

            sb.append(INDENT1 + "}\n");
        }
    }

    private static class Protocol extends Section
    {
        private final AttributeT<Integer> m_version;
        private final AttributeT<Type> m_versionType;
        private final AttributeT<Type> m_messageIdType;
        private final AttributeT<Type> m_messageSizeType;
        private final AttributeT<Type> m_stringSizeType;
        private final AttributeT<String> m_stringEncoding;
        private final AttributeT<String> m_package;
        private final MultipleSectionT<Message> m_messages;

        public Protocol()
        {
            super("protocol");
            m_version = new AttributeT<Integer>("version", /*mandatory*/true, s_integerCoder, null);
            m_versionType = new AttributeT<Type>("version_type", /*mandatory*/true, s_typeCoder, null);
            m_messageIdType = new AttributeT<Type>("message_id_type", /*mandatory*/true, s_typeCoder, null);
            m_messageSizeType = new AttributeT<Type>("message_size_type", /*mandatory*/true, s_typeCoder, null);
            m_stringSizeType = new AttributeT<Type>("string_size_type", /*mandatory*/true, s_typeCoder, null);
            m_stringEncoding = new AttributeT<String>("string_encoding", /*mandatory*/true, s_stringCoder, null);
            m_package = new AttributeT<String>("package", /*mandatory*/false, s_stringCoder, null);
            m_messages = new MultipleSectionT<Message>("message", Message.class);
        }

        public String processElement(XMLStreamReader xmlSR) throws XMLStreamException, IllegalArgumentException
        {
            final Attribute [] attributes = new Attribute [] {
                    m_version, m_versionType, m_messageIdType, m_messageSizeType, m_stringSizeType, m_stringEncoding, m_package};
            final Section [] sections = new Section [] {m_messages};
            processElement(xmlSR, getSectionName(), attributes, sections);
            return null;
        }
        
        public void dump(StringBuilder sb)
        {
            sb.append("package " + m_package.getValue() + ";\n\n");
            sb.append("import java.nio.ByteOrder;\n");
            sb.append("import java.nio.ByteBuffer;\n");
            sb.append("import java.nio.charset.Charset;\n");
            sb.append("import java.security.InvalidParameterException;\n");
            sb.append("import org.jsl.collider.RetainableByteBuffer;\n");
            sb.append("import org.jsl.collider.RetainableByteBufferPool;\n\n");

            final Type messageSizeType = m_messageSizeType.getValue();
            final Type messageIdType = m_messageIdType.getValue();

            sb.append("public class Protocol\n");
            sb.append("{\n");
            sb.append("    private static final Charset CHARSET = Charset.forName(\"" + m_stringEncoding.getValue() + "\");\n\n");
            sb.append("    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;\n");
            sb.append("    public static final " + m_versionType.getValue().getJavaType() + " VERSION = " + m_version.getValue() + ";\n\n");

            sb.append("    public static class Message\n");
            sb.append("    {\n");
            sb.append("        public static final int HEADER_SIZE = (" + messageSizeType.getJavaSizeType() + ".SIZE/Byte.SIZE) + (" + messageIdType.getJavaSizeType() + ".SIZE/Byte.SIZE);\n\n");
            sb.append("        public static RetainableByteBuffer create(RetainableByteBufferPool pool, " + m_messageIdType.getValue().getName() + " id, int extSize)\n");
            sb.append("        {\n");
            sb.append("            if (extSize > (" + messageSizeType.getJavaSizeType() + ".MAX_VALUE - HEADER_SIZE))\n");
            sb.append("                throw new InvalidParameterException();\n");
            sb.append("            final int messageSize = (HEADER_SIZE + extSize);\n");
            sb.append("            final RetainableByteBuffer msg = pool.alloc(messageSize);\n");
            sb.append("            msg.put" + messageSizeType.getPutSuffix() + "((" + messageSizeType.getName() + ")messageSize);\n");
            sb.append("            msg.put" + messageIdType.getPutSuffix() + "(id);\n");
            sb.append("            return msg;\n");
            sb.append("        }\n\n");
            sb.append("        public static ByteBuffer create(" + m_messageIdType.getValue().getName() + " id, int extSize)\n");
            sb.append("        {\n");
            sb.append("            if (extSize > (" + messageSizeType.getJavaSizeType() + ".MAX_VALUE - HEADER_SIZE))\n");
            sb.append("                throw new InvalidParameterException();\n");
            sb.append("            final int messageSize = (HEADER_SIZE + extSize);\n");
            sb.append("            final ByteBuffer msg = ByteBuffer.allocateDirect(messageSize);\n");
            sb.append("            msg.put" + messageSizeType.getPutSuffix() + "((" + messageSizeType.getName() + ")messageSize);\n");
            sb.append("            msg.put" + messageIdType.getPutSuffix() + "(id);\n");
            sb.append("            return msg;\n");
            sb.append("        }\n\n");
            sb.append("        public static " + messageSizeType.getJavaType() + " getMessageSize(ByteBuffer msg)\n");
            sb.append("        {\n");
            sb.append("            return msg.get" + messageSizeType.getGetSuffix() + "(msg.position());\n");
            sb.append("        }\n\n");
            sb.append("        public static " + messageIdType.getJavaType() + " getMessageId(RetainableByteBuffer msg)\n");
            sb.append("        {\n");
            sb.append("             return msg.get" + messageIdType.getGetSuffix() + "(msg.position() + (" + messageIdType.getJavaSizeType() + ".SIZE/Byte.SIZE));\n");
            sb.append("        }\n");
            sb.append("    }\n\n");

            final Collection<Message> messages = m_messages.getSections();
            int msgs = 0;
            for (Message m : messages)
            {
                if (msgs > 0)
                    sb.append("\n");
                m.dump(sb, messageIdType, m_stringSizeType.getValue());
                msgs++;
            }

            sb.append("}\n");
        }
    }

    private static Protocol processFile(String fileName)
    {
        try
        {
            final XMLInputFactory xmlIF = XMLInputFactory.newInstance();
            final XMLStreamReader xmlSR = xmlIF.createXMLStreamReader(fileName, new FileInputStream(fileName));
            final Protocol protocol = new Protocol();

            while (xmlSR.hasNext())
            {
                final int eventType = xmlSR.next();
                switch (eventType)
                {
                    case XMLStreamConstants.START_ELEMENT:
                    {
                        final String name = xmlSR.getName().toString();
                        if (name.equals(protocol.getSectionName()))
                        {
                            if (s_logger.isLoggable(Level.FINE))
                                s_logger.log(Level.FINE, "START_ELEMENT: " + name);
                            protocol.processElement(xmlSR);
                        }
                    }
                    break;

                    case XMLStreamConstants.END_ELEMENT:
                    {
                        final String name = xmlSR.getName().toString();
                        if (name.equals(protocol.getSectionName()))
                        {
                            if (s_logger.isLoggable(Level.FINE))
                                s_logger.log(Level.FINE, "END_ELEMENT: " + name);
                        }
                    }
                    break;

                    case XMLStreamConstants.PROCESSING_INSTRUCTION:
                        System.out.println("PROCESSING_INSTRUCTION");
                    break;

                    case XMLStreamConstants.CHARACTERS:
                        System.out.println("CHARACTERS");
                    break;

                    case XMLStreamConstants.COMMENT:
                        System.out.println("COMMENT");
                    break;

                    case XMLStreamConstants.SPACE:
                        System.out.println("SPACE");
                    break;

                    case XMLStreamConstants.START_DOCUMENT:
                        System.out.println("START_DOCUMENT");
                    break;

                    case XMLStreamConstants.END_DOCUMENT:
                        if (s_logger.isLoggable(Level.FINE))
                            s_logger.log(Level.FINE, "END_DOCUMENT");
                    break;

                    case XMLStreamConstants.ENTITY_REFERENCE:
                        System.out.println("ENTITY_REFERENCE");
                    break;

                    case XMLStreamConstants.ATTRIBUTE:
                        System.out.println("ATTRIBUTE");
                    break;

                    case XMLStreamConstants.DTD:
                        System.out.println("DTD");
                    break;

                    case XMLStreamConstants.CDATA:
                        System.out.println("CDATA");
                    break;

                    case XMLStreamConstants.NAMESPACE:
                        System.out.println("NAMESPACE");
                    break;

                    case XMLStreamConstants.NOTATION_DECLARATION:
                        System.out.println("NOTATION_DECLARATION");
                    break;

                    case XMLStreamConstants.ENTITY_DECLARATION:
                        System.out.println("ENTITY_DECLARATION");
                    break;

                    default:
                        //System.out.println("Unknown eventType=" + eventType);
                }
            }

            xmlSR.close();
            return protocol;
        }
        catch (final XMLStreamException|FileNotFoundException ex)
        {
            ex.printStackTrace();
        }
        return null;
    }

    public static void main(String [] args)
    {
        if (args.length > 0)
        {
            final Protocol protocol = processFile(args[0]);
            if (protocol != null)
            {
                final StringBuilder sb = new StringBuilder();
                protocol.dump(sb);
                if (args.length > 1)
                {
                    final File file = new File(args[1]);
                    try
                    {
                        final File parentFile = file.getParentFile();
                        if (parentFile != null)
                            parentFile.mkdirs();

                        final BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                        writer.append(sb);
                        writer.close();
                    }
                    catch (final IOException ex)
                    {
                        ex.printStackTrace();
                    }
                }
                else
                    System.out.println(sb.toString());
            }
        }
        else
            System.out.println("Usage: prtgen <input file>");
    }
}
