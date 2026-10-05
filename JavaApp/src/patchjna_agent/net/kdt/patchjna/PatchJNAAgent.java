package net.kdt.patchjna;

import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class PatchJNAAgent implements ClassFileTransformer {

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) throws IllegalClassFormatException {

        byte[] transformedByteCode = classfileBuffer;

        if ("com/sun/jna/Platform".equals(className)) {
            System.out.println("PatchJNAAgent: Replacing JNA Platform class");

            try {
                InputStream inputStream =
                        PatchJNAAgent.class.getClassLoader()
                                .getResourceAsStream("com/sun/jna/Platform.class.patch");

                if (inputStream == null) {
                    System.err.println("PatchJNAAgent: Platform.class.patch not found");
                    return classfileBuffer;
                }

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;

                while ((read = inputStream.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }

                inputStream.close();
                transformedByteCode = output.toByteArray();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if ("com/mojang/blaze3d/platform/MacosUtil".equals(className)) {
            System.out.println(
                    "PatchJNAAgent: Patching MacosUtil.disableCloseWindowMenuItem()"
            );

            try {
                transformedByteCode = patchMacosUtil(classfileBuffer);
            } catch (Exception e) {
                System.err.println("PatchJNAAgent: Failed to patch MacosUtil");
                e.printStackTrace();
            }
        }

        return transformedByteCode;
    }

    private static byte[] patchMacosUtil(byte[] original) throws Exception {
        DataInputStream in =
                new DataInputStream(new ByteArrayInputStream(original));

        int magic = in.readInt();

        if (magic != 0xCAFEBABE) {
            return original;
        }

        int minor = in.readUnsignedShort();
        int major = in.readUnsignedShort();
        int constantPoolCount = in.readUnsignedShort();

        String[] utf8 = new String[constantPoolCount];
        byte[][] constantPoolRaw = new byte[constantPoolCount][];

        for (int i = 1; i < constantPoolCount; i++) {
            int tag = in.readUnsignedByte();

            ByteArrayOutputStream cpOut = new ByteArrayOutputStream();
            DataOutputStream cpData = new DataOutputStream(cpOut);

            cpData.writeByte(tag);

            switch (tag) {
                case 1: {
                    int length = in.readUnsignedShort();
                    byte[] data = new byte[length];
                    in.readFully(data);

                    cpData.writeShort(length);
                    cpData.write(data);

                    utf8[i] = new String(data, "UTF-8");
                    break;
                }

                case 3:
                case 4:
                    cpData.writeInt(in.readInt());
                    break;

                case 5:
                case 6:
                    cpData.writeLong(in.readLong());
                    constantPoolRaw[i] = cpOut.toByteArray();
                    i++;
                    constantPoolRaw[i] = new byte[0];
                    continue;

                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    cpData.writeShort(in.readUnsignedShort());
                    break;

                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    cpData.writeShort(in.readUnsignedShort());
                    cpData.writeShort(in.readUnsignedShort());
                    break;

                case 15:
                    cpData.writeByte(in.readUnsignedByte());
                    cpData.writeShort(in.readUnsignedShort());
                    break;

                default:
                    throw new IOException(
                            "Unknown constant-pool tag: " + tag
                    );
            }

            constantPoolRaw[i] = cpOut.toByteArray();
        }

        ByteArrayOutputStream resultBytes =
                new ByteArrayOutputStream();

        DataOutputStream out =
                new DataOutputStream(resultBytes);

        out.writeInt(magic);
        out.writeShort(minor);
        out.writeShort(major);
        out.writeShort(constantPoolCount);

        for (int i = 1; i < constantPoolCount; i++) {
            if (constantPoolRaw[i] != null) {
                out.write(constantPoolRaw[i]);
            }
        }

        out.writeShort(in.readUnsignedShort());
        out.writeShort(in.readUnsignedShort());
        out.writeShort(in.readUnsignedShort());

        int interfacesCount = in.readUnsignedShort();
        out.writeShort(interfacesCount);

        for (int i = 0; i < interfacesCount; i++) {
            out.writeShort(in.readUnsignedShort());
        }

        int fieldsCount = in.readUnsignedShort();
        out.writeShort(fieldsCount);

        copyMembers(in, out, fieldsCount);

        int methodsCount = in.readUnsignedShort();
        out.writeShort(methodsCount);

        boolean patched = false;

        for (int m = 0; m < methodsCount; m++) {
            int accessFlags = in.readUnsignedShort();
            int nameIndex = in.readUnsignedShort();
            int descriptorIndex = in.readUnsignedShort();
            int attributesCount = in.readUnsignedShort();

            out.writeShort(accessFlags);
            out.writeShort(nameIndex);
            out.writeShort(descriptorIndex);
            out.writeShort(attributesCount);

            String methodName =
                    nameIndex < utf8.length ? utf8[nameIndex] : null;

            String descriptor =
                    descriptorIndex < utf8.length
                            ? utf8[descriptorIndex]
                            : null;

            boolean target =
                    "disableCloseWindowMenuItem".equals(methodName)
                    && "()V".equals(descriptor);

            for (int a = 0; a < attributesCount; a++) {
                int attributeNameIndex = in.readUnsignedShort();
                int attributeLength = in.readInt();

                String attributeName =
                        attributeNameIndex < utf8.length
                                ? utf8[attributeNameIndex]
                                : null;

                byte[] attributeData = new byte[attributeLength];
                in.readFully(attributeData);

                if (target && "Code".equals(attributeName)) {
                    byte[] patchedCode =
                            makeNoOpCode(attributeData);

                    out.writeShort(attributeNameIndex);
                    out.writeInt(patchedCode.length);
                    out.write(patchedCode);

                    patched = true;

                    System.out.println(
                            "PatchJNAAgent: MacosUtil method patched successfully"
                    );
                } else {
                    out.writeShort(attributeNameIndex);
                    out.writeInt(attributeLength);
                    out.write(attributeData);
                }
            }
        }

        int classAttributes = in.readUnsignedShort();
        out.writeShort(classAttributes);

        for (int i = 0; i < classAttributes; i++) {
            int nameIndex = in.readUnsignedShort();
            int length = in.readInt();

            byte[] data = new byte[length];
            in.readFully(data);

            out.writeShort(nameIndex);
            out.writeInt(length);
            out.write(data);
        }

        out.flush();

        if (!patched) {
            System.err.println(
                    "PatchJNAAgent: disableCloseWindowMenuItem() was not found"
            );
            return original;
        }

        return resultBytes.toByteArray();
    }

    private static void copyMembers(
            DataInputStream in,
            DataOutputStream out,
            int count) throws IOException {

        for (int i = 0; i < count; i++) {
            out.writeShort(in.readUnsignedShort());
            out.writeShort(in.readUnsignedShort());
            out.writeShort(in.readUnsignedShort());

            int attributes = in.readUnsignedShort();
            out.writeShort(attributes);

            for (int a = 0; a < attributes; a++) {
                int name = in.readUnsignedShort();
                int length = in.readInt();

                byte[] data = new byte[length];
                in.readFully(data);

                out.writeShort(name);
                out.writeInt(length);
                out.write(data);
            }
        }
    }

private static byte[] makeNoOpCode(
        byte[] codeAttribute) throws IOException {

    DataInputStream in =
            new DataInputStream(
                    new ByteArrayInputStream(codeAttribute)
            );

    // Read the original Code attribute header.
    in.readUnsignedShort(); // original max_stack
    int maxLocals = in.readUnsignedShort();
    int codeLength = in.readInt();

    if (codeLength < 1) {
        throw new IOException(
                "MacosUtil method has empty bytecode"
        );
    }

    // The original bytecode, exception table, and nested
    // Code attributes are intentionally discarded.
    //
    // We replace the entire method body with a single
    // RETURN instruction.
    ByteArrayOutputStream output =
            new ByteArrayOutputStream();

    DataOutputStream out =
            new DataOutputStream(output);

    out.writeShort(0);       // max_stack
    out.writeShort(maxLocals);
    out.writeInt(1);         // code_length
    out.writeByte(0xB1);     // RETURN
    out.writeShort(0);       // exception_table_length
    out.writeShort(0);       // attributes_count

    out.flush();

    return output.toByteArray();
}

    public static void premain(
            String args,
            Instrumentation instrumentation) {

        System.out.println("PatchJNAAgent: premain called");

        instrumentation.addTransformer(
                new PatchJNAAgent()
        );
    }
}