package cn.ac.ios.justin.emit;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueCodeTest {

    @Test
    void primitiveLiterals() {
        assertEquals("(byte) -3", ValueCode.of(PrimitiveValue.of((byte) -3)).toString());
        assertEquals("'\\n'", ValueCode.of(PrimitiveValue.of('\n')).toString());
        assertEquals("'\\u0010'", ValueCode.of(PrimitiveValue.of('')).toString());
        assertEquals("42L", ValueCode.of(PrimitiveValue.of(42L)).toString());
        assertEquals("Long.MIN_VALUE", ValueCode.of(PrimitiveValue.of(Long.MIN_VALUE)).toString());
        assertEquals("1.5F", ValueCode.of(PrimitiveValue.of(1.5f)).toString());
        assertEquals("Double.NaN", ValueCode.of(PrimitiveValue.of(Double.NaN)).toString());
        assertEquals("-0.25D", ValueCode.of(PrimitiveValue.of(-0.25)).toString());
    }

    @Test
    void stringsArraysAndNulls() {
        assertEquals("\"a\\tb\"", ValueCode.of(new Value.StringValue("a\tb")).toString());
        // JavaPoet splits strings at newlines into concatenations; both halves must survive
        assertTrue(ValueCode.of(new Value.StringValue("a\nb")).toString().contains("\"a\\n\""));
        assertEquals("(java.lang.String) null", ValueCode.of(new Value.NullValue(TypeRef.STRING)).toString());
        Value arr = new Value.ArrayValue(TypeRef.INT, List.of(PrimitiveValue.of(1), PrimitiveValue.of(2)));
        assertEquals("new int[]{1, 2}", ValueCode.of(arr).toString());
    }

    @Test
    void nestedClassNames() {
        assertEquals("java.util.Map.Entry", TypeNames.className("java.util.Map$Entry").toString());
        TypeRef list = new TypeRef.ClassRef("java.util.List", List.of(TypeRef.INT));
        assertEquals("java.util.List<java.lang.Integer>", TypeNames.of(list).toString()); // primitives are boxed as type args
    }

    @Test
    void recipes() {
        Value boxed = new Value.ObjectValue(TypeRef.ClassRef.of("java.lang.Integer"), new Value.Recipe.Boxed(PrimitiveValue.of(7)));
        assertEquals("java.lang.Integer.valueOf(7)", ValueCode.of(boxed).toString());
        Value list = new Value.ObjectValue(new TypeRef.ClassRef("java.util.ArrayList", List.of(TypeRef.STRING)),
                new Value.Recipe.ConstructorCall("java.util.ArrayList", "()V", List.of()));
        assertEquals("new java.util.ArrayList<java.lang.String>()", ValueCode.of(list).toString());
    }
}
