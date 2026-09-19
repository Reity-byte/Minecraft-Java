package mc;

import org.joml.Matrix4f;

/**
 * Simple free-fly camera: position + yaw/pitch, no gravity/collision yet.
 * That's your next step once this base feels comfortable.
 */
public class Camera {

    public float x = 8, y = 80, z = 8; // start above the terrain (ground is around y=64)
    public float yaw = -90f;   // facing -Z initially
    public float pitch = 0f;

    private final float mouseSensitivity = 0.12f;

    public void processMouse(double dx, double dy) {
        yaw += (float) (dx * mouseSensitivity);
        pitch += (float) (dy * mouseSensitivity);

        if (pitch > 89f) pitch = 89f;
        if (pitch < -89f) pitch = -89f;
    }

    public void setPosition(float x, float y, float z)
    {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public float[] getForwardMoveTarget(float amount)
    {
        float[] f = forwardVector();
        float newX = x + f[0] * amount;
        float newZ = z + f[2] * amount;
        return new float[]{newX, y, newZ};
    }

    public float[]getRightMoveTarget(float amount)
    {
        float[] r = rightVector();
        float newX = x + r[0] * amount;
        float newZ = z + r[2] * amount;

        return new float[]{newX, y, newZ};
    }

    public float[]getUpMoveTarget(float amount)
    {
        float newY = y + amount;
        return new float[]{x, newY, z};
    }

    private float[] forwardVector() {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float fx = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
        float fy = (float) (Math.sin(pitchRad));
        float fz = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        return new float[]{fx, fy, fz};
    }

    public float[] getLookDirection() {
        return forwardVector();
    }

    private float[] rightVector() {
        float[] f = forwardVector();
        // right = forward x worldUp
        float ux = 0, uy = 1, uz = 0;
        float rx = f[1] * uz - f[2] * uy;
        float ry = f[2] * ux - f[0] * uz;
        float rz = f[0] * uy - f[1] * ux;
        float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        return new float[]{rx / len, ry / len, rz / len};
    }

    public void moveForward(float amount) {
        float[] f = getForwardMoveTarget(amount);
        setPosition(f[0], f[1], f[2]);
    }

    public void moveRight(float amount) {
        float[] r = getRightMoveTarget(amount);
        setPosition(r[0], r[1], r[2]);
    }

    public void moveUp(float amount) {
        float[] u = getUpMoveTarget(amount);
        setPosition(u[0], u[1], u[2]);
    }

    /**
     * View matice pro shader. Nahradila původní applyView(), která tlačila
     * matici rovnou do fixed-function GL - to v core profilu neexistuje.
     *
     * Všimni si, že oko je v POČÁTKU, ne na pozici kamery. Renderer posílá
     * geometrii relativně ke kameře (viz komentář v Shaders), takže tahle
     * matice obsahuje jen rotaci, žádný posun. Kdyby tu byla i translace,
     * započítala by se dvakrát.
     */
    public Matrix4f viewMatrix(Matrix4f dest)
    {
        float[] f = forwardVector();
        return dest.setLookAt(0, 0, 0,
                f[0], f[1], f[2],
                0, 1, 0);
    }
}
