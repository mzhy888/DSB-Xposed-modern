package com.dsb.xp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 新 API 没有 XposedHelpers，这里用最朴素的反射代替。 */
final class Refl {
    private Refl() {}

    static Class<?> findClass(String name, ClassLoader cl) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    static Object callStatic(Class<?> c, String name, Object... args) throws Exception {
        Method m = findMethod(c, name, args);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    static Object call(Object target, String name, Object... args) throws Exception {
        Method m = findMethod(target.getClass(), name, args);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    static Object newInstance(Class<?> c) throws Exception {
        Constructor<?> k = c.getDeclaredConstructor();
        k.setAccessible(true);
        return k.newInstance();
    }

    static void setStatic(Class<?> c, String field, Object value) throws Exception {
        Field f = null;
        for (Class<?> k = c; k != null && f == null; k = k.getSuperclass()) {
            try {
                f = k.getDeclaredField(field);
            } catch (NoSuchFieldException ignored) {
            }
        }
        if (f == null) throw new NoSuchFieldException(c.getName() + "#" + field);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static Method findMethod(Class<?> c, String name, Object[] args) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != args.length) continue;
                boolean ok = true;
                for (int i = 0; i < pt.length; i++) {
                    if (!accepts(pt[i], args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) return m;
            }
        }
        throw new NoSuchMethodException(c.getName() + "#" + name);
    }

    private static boolean accepts(Class<?> t, Object a) {
        if (a == null) return !t.isPrimitive();
        if (!t.isPrimitive()) return t.isInstance(a);
        if (t == int.class) return a instanceof Integer;
        if (t == long.class) return a instanceof Long || a instanceof Integer;
        if (t == boolean.class) return a instanceof Boolean;
        if (t == double.class) return a instanceof Double || a instanceof Float;
        if (t == float.class) return a instanceof Float;
        if (t == short.class) return a instanceof Short;
        if (t == byte.class) return a instanceof Byte;
        if (t == char.class) return a instanceof Character;
        return false;
    }
}
