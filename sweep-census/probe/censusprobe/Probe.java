package censusprobe;

import java.io.PrintStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.constant.ClassDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

// Census agent: counts entries to every method of Regulator's production classes (those loaded from
// a .../target/classes/ directory) and, when -Dio.airlift.regulator.dfa.route-counts=true, every
// Dfa.countRoute(dfa, route) call by route. Times nothing; the counts are read by the census driver.
public final class Probe
{
    public static final long[] COUNTS = new long[1 << 17];
    public static final long[] ROUTES = new long[64];
    private static final List<String> NAMES = new ArrayList<>();
    private static final ClassDesc PROBE = ClassDesc.of("censusprobe.Probe");
    private static final ClassDesc LONG_ARRAY = ClassDesc.ofDescriptor("[J");
    public static volatile int failures;

    private Probe() {}

    public static synchronized String name(int id)
    {
        return NAMES.get(id);
    }

    public static synchronized int size()
    {
        return NAMES.size();
    }

    private static synchronized int register(String name)
    {
        NAMES.add(name);
        return NAMES.size() - 1;
    }

    public static void reset()
    {
        java.util.Arrays.fill(COUNTS, 0);
        java.util.Arrays.fill(ROUTES, 0);
    }

    public static void dump(String phase, PrintStream out)
    {
        int n = size();
        for (int i = 0; i < n; i++) {
            if (COUNTS[i] != 0) {
                out.println("M\t" + phase + "\t" + name(i) + "\t" + COUNTS[i]);
            }
        }
        for (int i = 0; i < ROUTES.length; i++) {
            if (ROUTES[i] != 0) {
                out.println("R\t" + phase + "\t" + i + "\t" + ROUTES[i]);
            }
        }
    }

    public static void premain(String args, Instrumentation instrumentation)
    {
        instrumentation.addTransformer(new Transformer());
    }

    private static final class Transformer
            implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain, byte[] bytes)
        {
            if (className == null || !className.startsWith("io/airlift/regulator/") || domain == null || domain.getCodeSource() == null) {
                return null;
            }
            String location = String.valueOf(domain.getCodeSource().getLocation());
            if (!location.endsWith("/target/classes/")) {
                return null;
            }
            try {
                ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                        ClassHierarchyResolver.ofResourceParsing(loader).orElse(ClassHierarchyResolver.defaultResolver())));
                ClassModel model = classFile.parse(bytes);
                String owner = className.substring("io/airlift/regulator/".length()).replace('/', '.');
                return classFile.transformClass(model, (classBuilder, element) -> {
                    if (element instanceof MethodModel method && method.findAttribute(java.lang.classfile.Attributes.code()).isPresent()) {
                        String methodName = method.methodName().stringValue();
                        String descriptor = method.methodType().stringValue();
                        int id = register(owner + "." + methodName + descriptor);
                        boolean route = owner.equals("Dfa") && methodName.equals("countRoute");
                        classBuilder.transformMethod(method, MethodTransform.transformingCode(new CodeTransform()
                        {
                            @Override
                            public void atStart(CodeBuilder code)
                            {
                                code.getstatic(PROBE, "COUNTS", LONG_ARRAY).ldc(id).dup2().laload().lconst_1().ladd().lastore();
                                if (route) {
                                    code.getstatic(PROBE, "ROUTES", LONG_ARRAY).iload(1).dup2().laload().lconst_1().ladd().lastore();
                                }
                            }

                            @Override
                            public void accept(CodeBuilder code, CodeElement codeElement)
                            {
                                code.with(codeElement);
                            }
                        }));
                    }
                    else {
                        classBuilder.with(element);
                    }
                });
            }
            catch (Throwable e) {
                failures++;
                System.err.println("censusprobe: failed to instrument " + className + ": " + e);
                return null;
            }
        }
    }
}
