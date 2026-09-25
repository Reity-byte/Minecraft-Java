package mc;

import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;

/**
 * Zkompilovaný a slinkovaný shader program plus nastavování uniformů.
 *
 * V core profilu neexistuje fixed-function pipeline - žádné glMatrixMode,
 * glColor3f ani GL_FOG. Všechno, co dřív dělal ovladač, se teď musí napsat
 * ručně do shaderů, a tahle třída je k tomu obal.
 */
public class ShaderProgram {

    private final int programId;

    /**
     * glGetUniformLocation je dotaz na ovladač, takže se neptáme každý frame.
     * Lokace se po slinkování programu nemění, stačí si je zapamatovat.
     */
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    public ShaderProgram(String vertexSource, String fragmentSource)
    {
        int vertexShader = compile(GL_VERTEX_SHADER, vertexSource, "vertex");
        int fragmentShader;

        try
        {
            fragmentShader = compile(GL_FRAGMENT_SHADER, fragmentSource, "fragment");
        }
        catch(RuntimeException e)
        {
            // Při chybě uklidit, co už vzniklo - výjimka jinak nechá v GL
            // viset shader objekty a program, na které nikdo nemá odkaz.
            glDeleteShader(vertexShader);
            throw e;
        }

        programId = glCreateProgram();
        glAttachShader(programId, vertexShader);
        glAttachShader(programId, fragmentShader);
        glLinkProgram(programId);

        if(glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE)
        {
            String log = glGetProgramInfoLog(programId);
            glDeleteProgram(programId);
            glDeleteShader(vertexShader);
            glDeleteShader(fragmentShader);
            throw new RuntimeException("Shader linking failed:\n" + log);
        }

        // Po slinkování jsou jednotlivé shadery zbytečné - program si drží svou kopii.
        glDetachShader(programId, vertexShader);
        glDetachShader(programId, fragmentShader);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
    }

    private static int compile(int type, String source, String label)
    {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        if(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
        {
            // Bez tohohle výpisu je ladění shaderů střelba naslepo:
            // špatný shader se nijak neprojeví, jen se nic nevykreslí.
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Compilation of " + label + " shader failed:\n" + log);
        }

        return shader;
    }

    public void bind()
    {
        glUseProgram(programId);
    }

    private int location(String name)
    {
        return uniformLocations.computeIfAbsent(name, n -> glGetUniformLocation(programId, n));
    }

    /** Pracovní pole pro setMatrix4 - bez alokace při každém volání (několikrát za frame). */
    private final float[] matrixValues = new float[16];

    /** Matice se do GL posílá jako 16 floatů po sloupcích - to už řeší JOML. */
    public void setMatrix4(String name, Matrix4f matrix)
    {
        matrix.get(matrixValues);
        glUniformMatrix4fv(location(name), false, matrixValues);
    }

    public void setVector2(String name, float x, float y)
    {
        glUniform2f(location(name), x, y);
    }

    public void setVector3(String name, float x, float y, float z)
    {
        glUniform3f(location(name), x, y, z);
    }

    public void setVector4(String name, float x, float y, float z, float w)
    {
        glUniform4f(location(name), x, y, z, w);
    }

    public void setFloat(String name, float value)
    {
        glUniform1f(location(name), value);
    }

    /** Pro sampler uniformy se posílá číslo texturovací jednotky, ne id textury. */
    public void setInt(String name, int value)
    {
        glUniform1i(location(name), value);
    }

    public void delete()
    {
        glDeleteProgram(programId);
    }
}
