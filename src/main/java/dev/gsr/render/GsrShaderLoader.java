package dev.gsr.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL43C.*;

/** Loads the compute shaders from assets/gsr/shaders, resolves #include and injects #define. */
public final class GsrShaderLoader {
    private static final String ROOT = "/assets/gsr/shaders/";
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*$", Pattern.MULTILINE);

    private GsrShaderLoader() {}

    public static String read(String name) {
        try (InputStream in = GsrShaderLoader.class.getResourceAsStream(ROOT + name)) {
            if (in == null) throw new IllegalStateException("shader introuvable : " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String preprocess(String name, Set<String> seen) {
        if (!seen.add(name)) return "";
        String src = read(name);
        Matcher m = INCLUDE.matcher(src);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(preprocess(m.group(1), seen)));
        m.appendTail(out);
        return out.toString();
    }

    public static int compileCompute(String name, int bindingBase) {
        String src = "#version 430 core\n"
                + "#define GSR_BINDING_FRAME "  + (bindingBase)     + "\n"
                + "#define GSR_BINDING_META "   + (bindingBase + 1) + "\n"
                + "#define GSR_BINDING_CELLS "  + (bindingBase + 2) + "\n"
                + "#define GSR_BINDING_SPLATS " + (bindingBase + 3) + "\n"
                + preprocess(name, new HashSet<>());
        int sh = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(sh, src);
        glCompileShader(sh);
        if (glGetShaderi(sh, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(sh);
            glDeleteShader(sh);
            throw new IllegalStateException("Compilation of " + name + " failed:\n" + log);
        }
        int prog = glCreateProgram();
        glAttachShader(prog, sh);
        glLinkProgram(prog);
        glDeleteShader(sh);
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(prog);
            glDeleteProgram(prog);
            throw new IllegalStateException("Linking of " + name + " failed:\n" + log);
        }
        return prog;
    }
}
