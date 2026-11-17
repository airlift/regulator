import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.nio.file.*;
import java.util.*;

// Dumps class \t method+descriptor \t source file \t first line \t last line \t bytecode size for every method.
public class MethodTable
{
    public static void main(String[] args) throws Exception
    {
        Path root = Path.of(args[0]);
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                ClassModel cm = ClassFile.of().parse(p);
                String cls = cm.thisClass().asInternalName().substring("io/airlift/regulator/".length()).replace('/', '.');
                String src = cm.findAttribute(Attributes.sourceFile()).map(s -> s.sourceFile().stringValue()).orElse("?");
                for (MethodModel m : cm.methods()) {
                    var code = m.findAttribute(Attributes.code());
                    if (code.isEmpty()) continue;
                    int lo = Integer.MAX_VALUE, hi = -1;
                    for (Attribute<?> a : code.get().attributes()) {
                        if (a instanceof LineNumberTableAttribute lnt) {
                            for (LineNumberInfo li : lnt.lineNumbers()) { lo = Math.min(lo, li.lineNumber()); hi = Math.max(hi, li.lineNumber()); }
                        }
                    }
                    System.out.println(cls + "\t" + m.methodName().stringValue() + m.methodType().stringValue() + "\t" + src + "\t" + (hi < 0 ? 0 : lo) + "\t" + (hi < 0 ? 0 : hi) + "\t" + code.get().codeLength());
                }
            }
        }
    }
}
