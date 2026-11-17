package io.airlift.regulator;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

// Static census: the route-selecting state of a compiled pattern, by reflection, two levels deep.
// Anchor revision (Nov 16): names the K1 placeholder apart and marks a RequiredBytes long-window
// search as own, other (beside-route) or none.
final class RouteState
{
    private RouteState() {}

    static String className(Object o)
    {
        Class<?> c = o.getClass();
        if (c.isHidden()) {
            return "GENERATED<" + c.getSuperclass().getSimpleName() + ">";
        }
        String name = c.getName();
        return name.startsWith("io.airlift.regulator.") ? name.substring("io.airlift.regulator.".length()) : c.getSimpleName();
    }

    static void describe(Object o, StringBuilder out, int depth)
            throws Exception
    {
        out.append(depth == 0 ? "class=" : "").append(depth == 0 ? className(o) : "");
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(o);
                String s = summarize(v);
                if (s == null) {
                    continue;
                }
                String key = (depth == 0 ? "" : "") + f.getName();
                out.append(';').append(key).append('=').append(s);
                if (depth == 0 && v != null && v.getClass().getName().startsWith("io.airlift.regulator.") && !(v instanceof Enum<?>)) {
                    if (v instanceof Prog prog) {
                        describeDfa(prog, out);
                        continue;
                    }
                    if (CensusRun.isPattern(v)) {
                        out.append(";{inner:");
                        describe(v, out, 0);
                        out.append('}');
                        continue;
                    }
                    StringBuilder inner = new StringBuilder();
                    describeFields(v, inner);
                    if (!inner.isEmpty()) {
                        out.append(";").append(f.getName()).append(".{").append(inner).append('}');
                    }
                }
            }
        }
    }

    static void describeFields(Object v, StringBuilder out)
            throws Exception
    {
        for (Class<?> k = v.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                String s = summarize(f.get(v));
                if (s != null) {
                    out.append(out.isEmpty() ? "" : ",").append(f.getName()).append('=').append(s);
                }
            }
        }
        if (v instanceof RequiredBytes) {
            out.append(out.isEmpty() ? "" : ",").append("longWindowLiteral=").append(longWindowLiteral(v));
        }
    }

    // Whether a RequiredBytes long-window search is for its own bytes (own), for another literal
    // chosen beside the pattern's literal routes by RequiredBytes.withBesideRouteSearch (other), or
    // absent (none). Only the beside-route search holds a literal other than the required bytes.
    static String longWindowLiteral(Object requiredBytes)
            throws Exception
    {
        Field searchField = RequiredBytes.class.getDeclaredField("longWindowSearch");
        Field bytesField = RequiredBytes.class.getDeclaredField("bytes");
        searchField.setAccessible(true);
        bytesField.setAccessible(true);
        LiteralSearchKernel search = (LiteralSearchKernel) searchField.get(requiredBytes);
        if (search == null) {
            return "none";
        }
        return java.util.Arrays.equals(search.literalBytes(), (byte[]) bytesField.get(requiredBytes)) ? "own" : "other";
    }

    static void describeDfa(Prog prog, StringBuilder out)
    {
        try {
            StringBuilder p = new StringBuilder();
            describeFields(prog, p);
            out.append(";prog.{").append(p.toString().replace(';', ',')).append('}');
        }
        catch (Throwable e) {
            out.append(";progError=").append(e.getClass().getSimpleName());
        }
    }

    static String summarize(Object v)
    {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b ? "true" : null;
        }
        if (v instanceof Number n) {
            long l = n.longValue();
            return l == 0 ? null : String.valueOf(l);
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        if (v instanceof String s) {
            return s.isEmpty() ? null : "str" + s.length();
        }
        if (v instanceof io.airlift.slice.Slice s) {
            return "slice" + s.length();
        }
        // The class counter's placeholder for "no counter" (BoundedCharacterClassCounter.unsupported())
        // is not an admitted counter; name it apart so the K1 admission rule can skip it.
        if (v instanceof BoundedCharacterClassCounter counter && counter == BoundedCharacterClassCounter.unsupported()) {
            return "UnsupportedBoundedCharacterClassCounter";
        }
        if (v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            return len == 0 ? null : v.getClass().getComponentType().getSimpleName() + "[" + len + "]";
        }
        if (v instanceof java.util.Collection<?> col) {
            return col.isEmpty() ? null : "coll" + col.size();
        }
        if (v instanceof java.util.Map<?, ?> m) {
            return m.isEmpty() ? null : "map" + m.size();
        }
        if (v instanceof java.util.Optional<?> opt) {
            return opt.isPresent() ? "present" : null;
        }
        return className(v);
    }
}
