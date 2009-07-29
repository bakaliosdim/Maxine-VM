/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.c1x.value;

/**
 * The <code>ConstType</code> class represents a value type for a constant on the stack
 * or in a local variable. This class implements the functionality provided by the
 * IntConstant, LongConstant, FloatConstant, etc classes in the C++ C1 compiler. This class
 * can represent either reference constants or primitive constants by boxing them.
 *
 * @author Ben L. Titzer
 */
public class ConstType extends ValueType {

    public static final ConstType NULL_OBJECT = new ConstType(BasicType.Object, null);
    public static final ConstType INT_MINUS_1 = forInt(-1);
    public static final ConstType INT_0 = forInt(0);
    public static final ConstType INT_1 = forInt(1);
    public static final ConstType INT_2 = forInt(2);
    public static final ConstType INT_3 = forInt(3);
    public static final ConstType INT_4 = forInt(4);
    public static final ConstType INT_5 = forInt(5);
    public static final ConstType LONG_0 = forLong(0);
    public static final ConstType LONG_1 = forLong(1);
    public static final ConstType FLOAT_0 = forFloat(0);
    public static final ConstType FLOAT_1 = forFloat(1);
    public static final ConstType FLOAT_2 = forFloat(2);
    public static final ConstType DOUBLE_0 = forDouble(0);
    public static final ConstType DOUBLE_1 = forDouble(1);

    private final Object value;

    /**
     * Create a new constant type represented by the specified object reference or boxed
     * primitive.
     * @param type the type of this constant
     * @param value the value of this constant
     */
    public ConstType(BasicType type, Object value) {
        super(type);
        this.value = value;
    }

    /**
     * Instances of this class are constants.
     * @return true
     */
    @Override
    public boolean isConstant() {
        return true;
    }

    /**
     * Checks whether this constant is non-null.
     * @return {@code true} if this constant is a primitive, or an object constant that is not null
     */
    public boolean isNonNull() {
        return value != null;
    }

    /**
     * Converts this value type to a string.
     */
    @Override
    public String toString() {
        final String val = isObject() ? "object@" + System.identityHashCode(value) : value.toString();
        return basicType.javaName + " = " + val;
    }

    /**
     * Gets this constant's value as a string.
     *
     * @return this constant's value as a string
     */
    public String valueString() {
        return value.toString();
    }

    /**
     * Returns the value of this constant as a boxed Java value.
     * @return the value of this constant
     */
    public Object boxedValue() {
        return value;
    }

    public boolean equivalent(ValueType other) {
        return other == this || other instanceof ConstType && valueEqual((ConstType) other);
    }

    private boolean valueEqual(ConstType cother) {
        // must have equivalent tags to be equal
        if (basicType != cother.basicType) {
            return false;
        }
        // use == for object references and .equals() for boxed types
        if (value == cother.value) {
            return true;
        } else if (!isObject() && value != null && value.equals(cother.value)) {
            return true;
        }
        return false;
    }

    /**
     * Converts this constant to a primitive int.
     * @return the int value of this constant
     */
    public int asInt() {
        if (basicType != BasicType.Object) {
            if (value instanceof Integer) {
                return (Integer) value;
            }
            if (value instanceof Byte) {
                return (Byte) value;
            }
            if (value instanceof Short) {
                return (Short) value;
            }
            if (value instanceof Character) {
                return (Character) value;
            }
            if (value instanceof Boolean) {
                return (Boolean) value ? 1 : 0; // note that we allow Boolean values to be used as ints
            }
        }
        throw new Error("Invalid constant");
    }

    /**
     * Converts this constant to a primitive long.
     * @return the long value of this constant
     */
    public long asLong() {
        if (basicType != BasicType.Object) {
            if (value instanceof Long) {
                return (Long) value;
            }
            if (value instanceof Integer) {
                return (Integer) value;
            }
            if (value instanceof Byte) {
                return (Byte) value;
            }
            if (value instanceof Short) {
                return (Short) value;
            }
            if (value instanceof Character) {
                return (Character) value;
            }
            if (value instanceof Boolean) {
                return (Boolean) value ? 1 : 0; // note that we allow Boolean values to be used as ints
            }
        }
        throw new Error("Invalid constant");
    }

    /**
     * Converts this constant to a primitive float.
     * @return the float value of this constant
     */
    public float asFloat() {
        if (basicType != BasicType.Object) {
            if (value instanceof Float) {
                return (Float) value;
            }
        }
        throw new Error("Invalid constant");
    }

    /**
     * Converts this constant to a primitive double.
     * @return the double value of this constant
     */
    public double asDouble() {
        if (basicType != BasicType.Object) {
            if (value instanceof Double) {
                return (Double) value;
            }
            if (value instanceof Float) {
                return (Float) value;
            }
        }
        throw new Error("Invalid constant");
    }

    /**
     * Converts this constant to the object reference it represents.
     * @return the object which this constant represents
     */
    public Object asObject() {
        if (basicType == BasicType.Object) {
            return value;
        }
        throw new Error("Invalid constant");
    }

    /**
     * Computes the hashcode of this constant.
     * @return a suitable hashcode for this constant
     */
    @Override
    public int hashCode() {
        if (basicType == BasicType.Object) {
            return System.identityHashCode(value);
        }
        return value.hashCode();
    }

    /**
     * Checks whether this constant equals another object. This is only
     * true if the other object is a constant and has the same value.
     * @param o the object to compare equality
     * @return <code>true</code> if this constant is equivalent to the specified object
     */
    @Override
    public boolean equals(Object o) {
        return o == this || o instanceof ConstType && valueEqual((ConstType) o);
    }

    /**
     * Checks whether this constant is the default value for its type.
     * @return <code>true</code> if the value is the default value for its type; <code>false</code> otherwise
     */
    public boolean isDefaultValue() {
        switch (basicType) {
            case Int: return asInt() == 0;
            case Long: return asLong() == 0;
            case Float: return asFloat() == 0.0f; // TODO: be careful about -0.0
            case Double: return asDouble() == 0.0d; // TODO: be careful about -0.0
            case Object: return asObject() == null;
        }
        return false;
    }

    /**
     * Utility method to create a value type for a double constant.
     * @param d the double value for which to create the value type
     * @return a value type representing the double
     */
    public static ConstType forDouble(double d) {
        return new ConstType(BasicType.Double, d);
    }

    /**
     * Utility method to create a value type for a float constant.
     * @param f the float value for which to create the value type
     * @return a value type representing the float
     */
    public static ConstType forFloat(float f) {
        return new ConstType(BasicType.Float, f);
    }

    /**
     * Utility method to create a value type for an long constant.
     * @param i the long value for which to create the value type
     * @return a value type representing the long
     */
    public static ConstType forLong(long i) {
        return new ConstType(BasicType.Long, i);
    }

    /**
     * Utility method to create a value type for an integer constant.
     * @param i the integer value for which to create the value type
     * @return a value type representing the integer
     */
    public static ConstType forInt(int i) {
        return new ConstType(BasicType.Int, i);
    }

    /**
     * Utility method to create a value type for a byte constant.
     * @param i the byte value for which to create the value type
     * @return a value type representing the byte
     */
    public static ConstType forByte(byte i) {
        return new ConstType(BasicType.Byte, i);
    }

    /**
     * Utility method to create a value type for a boolean constant.
     * @param i the boolean value for which to create the value type
     * @return a value type representing the boolean
     */
    public static ConstType forBoolean(boolean i) {
        return new ConstType(BasicType.Boolean, i);
    }

    /**
     * Utility method to create a value type for a char constant.
     * @param i the char value for which to create the value type
     * @return a value type representing the char
     */
    public static ConstType forChar(char i) {
        return new ConstType(BasicType.Char, i);
    }

    /**
     * Utility method to create a value type for a short constant.
     * @param i the short value for which to create the value type
     * @return a value type representing the short
     */
    public static ConstType forShort(short i) {
        return new ConstType(BasicType.Short, i);
    }

    /**
     * Utility method to create a value type for an address (jsr/ret address) constant.
     * @param i the address value for which to create the value type
     * @return a value type representing the address
     */
    public static ConstType forJsr(int i) {
        return new ConstType(BasicType.Jsr, i);
    }

    /**
     * Utility method to create a value type for a word constant.
     * @param i the number representing the word's value, either an {@link Integer} or a {@link Long}
     * @return a value type representing the word
     */
    public static ConstType forWord(Number i) {
        if (i instanceof Integer || i instanceof Long) {
            return new ConstType(BasicType.Word, i); // only Integer and Long are allowed
        }
        throw new IllegalArgumentException("cannot create word ConstType for object of type " + i.getClass());
    }

    /**
     * Utility method to create a value type for an object constant.
     * @param o the object value for which to create the value type
     * @return a value type representing the object
     */
    public static ConstType forObject(Object o) {
        if (o == null) {
            return NULL_OBJECT;
        }
        return new ConstType(BasicType.Object, o);
    }
}
