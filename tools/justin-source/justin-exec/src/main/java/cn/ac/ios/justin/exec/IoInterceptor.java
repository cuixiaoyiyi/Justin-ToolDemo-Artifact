package cn.ac.ios.justin.exec;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

/**
 * Rewrites call sites of side-effecting JDK entry points (file writes, sockets, processes, exit,
 * thread start) into SandboxSupport.blocked(...). Applied to every non-JDK class the sandbox
 * loads, so subject code and its dependencies are covered; calls that originate inside the JDK
 * itself are not, which is an accepted gap of the bytecode approach.
 */
public final class IoInterceptor {

    private static final String SUPPORT = SandboxSupport.class.getName().replace('.', '/');

    /** owner/name pairs (constructors as <init>) that are blocked outright. */
    private static final Set<String> BLOCKED = Set.of(
            "java/io/FileOutputStream.<init>", "java/io/FileWriter.<init>", "java/io/RandomAccessFile.<init>",
            "java/io/PrintWriter.<init>:file", "java/io/PrintStream.<init>:file",
            "java/nio/file/Files.write", "java/nio/file/Files.writeString", "java/nio/file/Files.newOutputStream",
            "java/nio/file/Files.newBufferedWriter", "java/nio/file/Files.createFile", "java/nio/file/Files.createDirectory",
            "java/nio/file/Files.createDirectories", "java/nio/file/Files.createTempFile", "java/nio/file/Files.createTempDirectory",
            "java/nio/file/Files.delete", "java/nio/file/Files.deleteIfExists", "java/nio/file/Files.move", "java/nio/file/Files.copy",
            "java/nio/file/Files.setAttribute", "java/nio/file/Files.setPosixFilePermissions", "java/nio/file/Files.setLastModifiedTime",
            "java/nio/channels/FileChannel.open",
            "java/io/File.delete", "java/io/File.deleteOnExit", "java/io/File.mkdir", "java/io/File.mkdirs", "java/io/File.createNewFile",
            "java/io/File.renameTo", "java/io/File.createTempFile", "java/io/File.setWritable", "java/io/File.setLastModified",
            "java/net/Socket.<init>", "java/net/ServerSocket.<init>", "java/net/DatagramSocket.<init>", "java/net/MulticastSocket.<init>",
            "java/net/URL.openConnection", "java/net/URL.openStream", "java/net/http/HttpClient.send", "java/net/http/HttpClient.sendAsync",
            "java/nio/channels/SocketChannel.open", "java/nio/channels/ServerSocketChannel.open",
            "java/lang/Runtime.exec", "java/lang/ProcessBuilder.start", "java/lang/ProcessBuilder.startPipeline",
            "java/lang/System.exit", "java/lang/Runtime.exit", "java/lang/Runtime.halt",
            "java/lang/Thread.start", "java/lang/System.setSecurityManager", "java/lang/System.load", "java/lang/System.loadLibrary",
            "java/lang/Runtime.load", "java/lang/Runtime.loadLibrary");

    private IoInterceptor() {}

    public static byte[] rewrite(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
                        String key = owner + "." + mname;
                        boolean block = BLOCKED.contains(key)
                                || (BLOCKED.contains(key + ":file") && (mdesc.startsWith("(Ljava/lang/String;") || mdesc.startsWith("(Ljava/io/File;")));
                        if (block) {
                            // leave the operand stack as the original call would after throwing: it never returns,
                            // so we throw before the call and keep the original instruction unreachable-but-valid
                            super.visitLdcInsn(owner.replace('/', '.') + "." + mname);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "blocked", "(Ljava/lang/String;)V", false);
                        }
                        super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(maxStack + 1, maxLocals);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    /** Classes that are never rewritten: the JDK and Justin's own support class. */
    public static boolean applies(String binaryName) {
        return !(binaryName.startsWith("java.") || binaryName.startsWith("javax.") || binaryName.startsWith("jdk.")
                || binaryName.startsWith("sun.") || binaryName.startsWith("com.sun.") || binaryName.startsWith("org.jacoco.")
                || binaryName.equals(SandboxSupport.class.getName()));
    }
}
