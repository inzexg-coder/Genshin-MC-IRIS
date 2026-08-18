package net.teyvat.client;
import net.minecraft.util.math.MathHelper;
public final class LocomotionClips {

    private static final float IDLE_DUR = 166.7f;
    private static final float[] IDLE_T = new float[]{0.000f, 0.140f, 0.290f, 0.430f, 0.570f, 0.710f, 0.860f, 1.000f};
    private static final float[] IDLE_rYaw = new float[]{-0.829031f, -0.832522f, -0.834267f, -0.832522f, -0.827286f, -0.837758f, -0.836013f, -0.829031f};
    private static final float[] IDLE_rPitch = new float[]{0.108210f, 0.109956f, 0.113446f, 0.115192f, 0.116937f, 0.111701f, 0.108210f, 0.108210f};
    private static final float[] IDLE_rRoll = new float[]{1.122247f, 1.123992f, 1.123992f, 1.123992f, 1.122247f, 1.118756f, 1.120501f, 1.122247f};
    private static final float[] IDLE_lYaw = new float[]{1.010546f, 1.012291f, 1.012291f, 1.012291f, 1.010546f, 1.008800f, 1.010546f, 1.010546f};
    private static final float[] IDLE_lPitch = new float[]{-0.047124f, -0.040143f, -0.036652f, -0.040143f, -0.041888f, -0.031416f, -0.040143f, -0.047124f};
    private static final float[] IDLE_lRoll = new float[]{1.221730f, 1.219985f, 1.218240f, 1.230457f, 1.235693f, 1.216494f, 1.221730f, 1.221730f};
    private static final float[] IDLE_bYaw = new float[]{-0.099484f, -0.102974f, -0.104720f, -0.104720f, -0.101229f, -0.106465f, -0.106465f, -0.099484f};
    private static final float[] IDLE_bPitch = new float[]{-0.031416f, -0.036652f, -0.038397f, -0.036652f, -0.033161f, -0.036652f, -0.034907f, -0.031416f};
    private static final float[] IDLE_bRoll = new float[]{0.094248f, 0.099484f, 0.099484f, 0.095993f, 0.092502f, 0.106465f, 0.106465f, 0.094248f};
    private static final float[] IDLE_hYaw = new float[]{0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f};
    private static final float[] IDLE_hPitch = new float[]{-0.020944f, -0.013963f, 0.000000f, -0.013963f, -0.017453f, -0.012217f, -0.013963f, -0.020944f};
    private static final float[] IDLE_hRoll = new float[]{-0.027925f, -0.038397f, -0.045379f, -0.033161f, -0.034907f, -0.033161f, -0.029671f, -0.027925f};
    private static final float[] IDLE_rlYaw = new float[]{-0.050615f, -0.050615f, -0.047124f, -0.045379f, -0.045379f, -0.047124f, -0.048869f, -0.050615f};
    private static final float[] IDLE_rlPitch = new float[]{-0.181514f, -0.183260f, -0.185005f, -0.186750f, -0.186750f, -0.186750f, -0.185005f, -0.181514f};
    private static final float[] IDLE_rlRoll = new float[]{-0.029671f, -0.031416f, -0.038397f, -0.043633f, -0.041888f, -0.036652f, -0.031416f, -0.029671f};
    private static final float[] IDLE_llYaw = new float[]{-0.139626f, -0.137881f, -0.139626f, -0.141372f, -0.137881f, -0.139626f, -0.139626f, -0.139626f};
    private static final float[] IDLE_llPitch = new float[]{0.284489f, 0.286234f, 0.286234f, 0.286234f, 0.282743f, 0.287979f, 0.287979f, 0.284489f};
    private static final float[] IDLE_llRoll = new float[]{0.139626f, 0.132645f, 0.122173f, 0.111701f, 0.116937f, 0.120428f, 0.127409f, 0.139626f};
    public static float sample_idle(float progress, float[] data) {
        float t = MathHelper.clamp(progress, 0f, 1f);
        float[] T = IDLE_T;
        if (t <= T[0]) return data[0];
        if (t >= T[T.length - 1]) return data[data.length - 1];
        for (int i = 0; i < T.length - 1; i++) {
            if (t <= T[i + 1]) {
                float span = T[i + 1] - T[i];
                float u = span <= 0 ? 0f : (t - T[i]) / span;
                return MathHelper.lerp(u, data[i], data[i + 1]);
            }
        }
        return data[data.length - 1];
    }
    public static float sample_idle_rYaw(float p) { return sample_idle(p, IDLE_rYaw); }
    public static float sample_idle_rPitch(float p) { return sample_idle(p, IDLE_rPitch); }
    public static float sample_idle_rRoll(float p) { return sample_idle(p, IDLE_rRoll); }
    public static float sample_idle_lYaw(float p) { return sample_idle(p, IDLE_lYaw); }
    public static float sample_idle_lPitch(float p) { return sample_idle(p, IDLE_lPitch); }
    public static float sample_idle_lRoll(float p) { return sample_idle(p, IDLE_lRoll); }
    public static float sample_idle_bYaw(float p) { return sample_idle(p, IDLE_bYaw); }
    public static float sample_idle_bPitch(float p) { return sample_idle(p, IDLE_bPitch); }
    public static float sample_idle_bRoll(float p) { return sample_idle(p, IDLE_bRoll); }
    public static float sample_idle_rlYaw(float p) { return sample_idle(p, IDLE_rlYaw); }
    public static float sample_idle_rlPitch(float p) { return sample_idle(p, IDLE_rlPitch); }
    public static float sample_idle_rlRoll(float p) { return sample_idle(p, IDLE_rlRoll); }
    public static float sample_idle_llYaw(float p) { return sample_idle(p, IDLE_llYaw); }
    public static float sample_idle_llPitch(float p) { return sample_idle(p, IDLE_llPitch); }
    public static float sample_idle_llRoll(float p) { return sample_idle(p, IDLE_llRoll); }

    private static final float WALK_DUR = 31.3f;
    private static final float[] WALK_T = new float[]{0.000f, 0.090f, 0.180f, 0.270f, 0.360f, 0.450f, 0.550f, 0.640f, 0.730f, 0.820f, 0.910f, 1.000f};
    private static final float[] WALK_rYaw = new float[]{-1.284562f, -1.043707f, -0.743510f, -0.593412f, -0.577704f, -0.848230f, -1.117011f, -1.214749f, -1.164135f, -1.183333f, -1.240929f, -1.284562f};
    private static final float[] WALK_rPitch = new float[]{-0.228638f, -0.626573f, -0.717330f, -0.719076f, -0.664970f, -0.427606f, -0.118682f, 0.087266f, 0.185005f, 0.188496f, 0.019199f, -0.228638f};
    private static final float[] WALK_rRoll = new float[]{1.122247f, 0.979130f, 0.701622f, 0.593412f, 0.631809f, 0.867429f, 1.019272f, 1.104793f, 1.157153f, 1.148427f, 1.101303f, 1.122247f};
    private static final float[] WALK_lYaw = new float[]{0.869174f, 1.069887f, 1.204277f, 1.214749f, 1.233948f, 1.179843f, 0.923279f, 0.584685f, 0.404916f, 0.448550f, 0.635300f, 0.869174f};
    private static final float[] WALK_lPitch = new float[]{0.328122f, 0.195477f, 0.038397f, 0.089012f, 0.139626f, 0.150098f, 0.307178f, 0.520108f, 0.575959f, 0.534071f, 0.408407f, 0.328122f};
    private static final float[] WALK_lRoll = new float[]{0.918043f, 1.052434f, 1.125737f, 1.132719f, 1.078613f, 1.026254f, 0.945968f, 0.717330f, 0.516617f, 0.541052f, 0.757473f, 0.918043f};
    private static final float[] WALK_bYaw = new float[]{-0.026180f, 0.000000f, 0.026180f, 0.052360f, 0.062832f, 0.057596f, 0.034907f, 0.010472f, -0.019199f, -0.047124f, -0.036652f, -0.026180f};
    private static final float[] WALK_bPitch = new float[]{-0.029671f, -0.083776f, -0.137881f, -0.191986f, -0.148353f, -0.078540f, 0.010472f, 0.083776f, 0.108210f, 0.123918f, 0.050615f, -0.029671f};
    private static final float[] WALK_bRoll = new float[]{0.012217f, 0.010472f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.012217f, 0.017453f, 0.000000f, -0.026180f, 0.000000f, 0.012217f};
    private static final float[] WALK_hYaw = new float[]{0.000000f, -0.031416f, -0.050615f, -0.085521f, -0.099484f, -0.083776f, -0.059341f, -0.036652f, -0.013963f, 0.000000f, 0.000000f, 0.000000f};
    private static final float[] WALK_hPitch = new float[]{0.062832f, 0.066323f, 0.069813f, 0.082030f, 0.061087f, 0.019199f, 0.000000f, -0.019199f, -0.033161f, -0.012217f, 0.019199f, 0.062832f};
    private static final float[] WALK_hRoll = new float[]{0.052360f, 0.061087f, 0.064577f, 0.038397f, 0.045379f, 0.078540f, 0.089012f, 0.061087f, 0.033161f, 0.024435f, 0.034907f, 0.052360f};
    private static final float[] WALK_rlYaw = new float[]{-0.075049f, -0.029671f, 0.026180f, 0.090757f, 0.101229f, 0.031416f, 0.000000f, 0.019199f, 0.022689f, -0.022689f, -0.082030f, -0.075049f};
    private static final float[] WALK_rlPitch = new float[]{-0.141372f, -0.183260f, -0.212930f, -0.218166f, -0.181514f, -0.151844f, -0.120428f, -0.109956f, -0.108210f, -0.094248f, -0.102974f, -0.141372f};
    private static final float[] WALK_rlRoll = new float[]{0.207694f, 0.026180f, -0.148353f, -0.249582f, -0.171042f, 0.178024f, 0.431096f, 0.572468f, 0.696386f, 0.715585f, 0.518363f, 0.207694f};
    private static final float[] WALK_llYaw = new float[]{-0.068068f, -0.043633f, -0.022689f, 0.000000f, 0.017453f, 0.024435f, 0.000000f, -0.040143f, -0.090757f, -0.130900f, -0.123918f, -0.068068f};
    private static final float[] WALK_llPitch = new float[]{0.144862f, 0.123918f, 0.108210f, 0.115192f, 0.106465f, 0.120428f, 0.172788f, 0.209440f, 0.202458f, 0.176278f, 0.150098f, 0.144862f};
    private static final float[] WALK_llRoll = new float[]{0.460767f, 0.558505f, 0.661480f, 0.699877f, 0.614356f, 0.363028f, 0.071558f, -0.108210f, -0.188496f, -0.108210f, 0.181514f, 0.460767f};
    public static float sample_walk(float progress, float[] data) {
        float t = MathHelper.clamp(progress, 0f, 1f);
        float[] T = WALK_T;
        if (t <= T[0]) return data[0];
        if (t >= T[T.length - 1]) return data[data.length - 1];
        for (int i = 0; i < T.length - 1; i++) {
            if (t <= T[i + 1]) {
                float span = T[i + 1] - T[i];
                float u = span <= 0 ? 0f : (t - T[i]) / span;
                return MathHelper.lerp(u, data[i], data[i + 1]);
            }
        }
        return data[data.length - 1];
    }
    public static float sample_walk_rYaw(float p) { return sample_walk(p, WALK_rYaw); }
    public static float sample_walk_rPitch(float p) { return sample_walk(p, WALK_rPitch); }
    public static float sample_walk_rRoll(float p) { return sample_walk(p, WALK_rRoll); }
    public static float sample_walk_lYaw(float p) { return sample_walk(p, WALK_lYaw); }
    public static float sample_walk_lPitch(float p) { return sample_walk(p, WALK_lPitch); }
    public static float sample_walk_lRoll(float p) { return sample_walk(p, WALK_lRoll); }
    public static float sample_walk_bYaw(float p) { return sample_walk(p, WALK_bYaw); }
    public static float sample_walk_bPitch(float p) { return sample_walk(p, WALK_bPitch); }
    public static float sample_walk_bRoll(float p) { return sample_walk(p, WALK_bRoll); }
    public static float sample_walk_rlYaw(float p) { return sample_walk(p, WALK_rlYaw); }
    public static float sample_walk_rlPitch(float p) { return sample_walk(p, WALK_rlPitch); }
    public static float sample_walk_rlRoll(float p) { return sample_walk(p, WALK_rlRoll); }
    public static float sample_walk_llYaw(float p) { return sample_walk(p, WALK_llYaw); }
    public static float sample_walk_llPitch(float p) { return sample_walk(p, WALK_llPitch); }
    public static float sample_walk_llRoll(float p) { return sample_walk(p, WALK_llRoll); }

    private static final float RUN_DUR = 16.7f;
    private static final float[] RUN_T = new float[]{0.000f, 0.090f, 0.180f, 0.270f, 0.360f, 0.450f, 0.550f, 0.640f, 0.730f, 0.820f, 0.910f, 1.000f};
    private static final float[] RUN_rYaw = new float[]{0.837758f, 1.132719f, 1.281072f, 1.211259f, 0.595157f, -0.146608f, -0.527089f, -0.500909f, -0.478220f, -0.301942f, 0.202458f, 0.834267f};
    private static final float[] RUN_rPitch = new float[]{-0.863938f, -0.762709f, -0.724312f, -0.795870f, -0.952950f, -0.986111f, -0.832522f, -0.773181f, -0.712094f, -0.808087f, -0.925025f, -0.863938f};
    private static final float[] RUN_rRoll = new float[]{-0.233874f, -0.350811f, -0.321141f, -0.240855f, 0.094248f, 0.602139f, 0.940732f, 0.965167f, 0.956440f, 0.792379f, 0.329867f, -0.230383f};
    private static final float[] RUN_lYaw = new float[]{-0.209440f, 0.069813f, 0.104720f, 0.104720f, -0.380482f, -0.996583f, -1.387537f, -1.473058f, -1.422443f, -1.275836f, -0.846485f, -0.209440f};
    private static final float[] RUN_lPitch = new float[]{1.073377f, 0.984366f, 0.958186f, 0.949459f, 1.008800f, 0.918043f, 0.733038f, 0.661480f, 0.717330f, 0.848230f, 1.019272f, 1.073377f};
    private static final float[] RUN_lRoll = new float[]{0.260054f, 0.532325f, 0.527089f, 0.499164f, 0.092502f, -0.375246f, -0.623083f, -0.630064f, -0.579449f, -0.499164f, -0.240855f, 0.258309f};
    private static final float[] RUN_bYaw = new float[]{-0.048869f, 0.019199f, 0.085521f, 0.120428f, 0.155334f, 0.116937f, 0.031416f, -0.029671f, -0.047124f, -0.054105f, -0.043633f, -0.047124f};
    private static final float[] RUN_bPitch = new float[]{-0.205949f, -0.254818f, -0.277507f, -0.179769f, -0.059341f, 0.066323f, 0.164061f, 0.207694f, 0.171042f, 0.111701f, -0.048869f, -0.205949f};
    private static final float[] RUN_bRoll = new float[]{0.235619f, 0.164061f, 0.106465f, 0.130900f, 0.148353f, 0.076794f, 0.000000f, -0.034907f, -0.029671f, 0.000000f, 0.123918f, 0.233874f};
    private static final float[] RUN_hYaw = new float[]{-0.029671f, -0.090757f, -0.150098f, -0.190241f, -0.193732f, -0.181514f, -0.122173f, -0.057596f, -0.026180f, -0.029671f, -0.026180f, -0.029671f};
    private static final float[] RUN_hPitch = new float[]{-0.054105f, 0.019199f, 0.092502f, 0.137881f, 0.125664f, 0.078540f, -0.010472f, -0.095993f, -0.130900f, -0.130900f, -0.115192f, -0.054105f};
    private static final float[] RUN_hRoll = new float[]{-0.556760f, -0.534071f, -0.516617f, -0.507891f, -0.511381f, -0.535816f, -0.548033f, -0.553269f, -0.561996f, -0.586431f, -0.581195f, -0.556760f};
    private static final float[] RUN_rlYaw = new float[]{-0.087266f, -0.026180f, 0.000000f, 0.000000f, 0.069813f, 0.043633f, 0.000000f, -0.013963f, 0.000000f, -0.040143f, -0.073304f, -0.089012f};
    private static final float[] RUN_rlPitch = new float[]{0.071558f, 0.043633f, 0.029671f, 0.012217f, -0.111701f, -0.218166f, -0.186750f, -0.075049f, -0.010472f, 0.020944f, 0.047124f, 0.071558f};
    private static final float[] RUN_rlRoll = new float[]{0.352557f, 0.085521f, -0.068068f, -0.040143f, 0.322886f, 0.794125f, 1.083849f, 1.076868f, 0.844739f, 0.766200f, 0.595157f, 0.350811f};
    private static final float[] RUN_llYaw = new float[]{0.024435f, 0.073304f, 0.102974f, 0.123918f, 0.253073f, 0.237365f, 0.150098f, 0.000000f, -0.031416f, -0.045379f, -0.010472f, 0.024435f};
    private static final float[] RUN_llPitch = new float[]{0.232129f, 0.136136f, 0.087266f, 0.034907f, 0.073304f, 0.040143f, 0.169297f, 0.235619f, 0.310669f, 0.328122f, 0.289725f, 0.232129f};
    private static final float[] RUN_llRoll = new float[]{0.815069f, 0.912807f, 0.790634f, 0.630064f, 0.630064f, 0.593412f, 0.387463f, 0.085521f, 0.041888f, 0.153589f, 0.467748f, 0.815069f};
    public static float sample_run(float progress, float[] data) {
        float t = MathHelper.clamp(progress, 0f, 1f);
        float[] T = RUN_T;
        if (t <= T[0]) return data[0];
        if (t >= T[T.length - 1]) return data[data.length - 1];
        for (int i = 0; i < T.length - 1; i++) {
            if (t <= T[i + 1]) {
                float span = T[i + 1] - T[i];
                float u = span <= 0 ? 0f : (t - T[i]) / span;
                return MathHelper.lerp(u, data[i], data[i + 1]);
            }
        }
        return data[data.length - 1];
    }
    public static float sample_run_rYaw(float p) { return sample_run(p, RUN_rYaw); }
    public static float sample_run_rPitch(float p) { return sample_run(p, RUN_rPitch); }
    public static float sample_run_rRoll(float p) { return sample_run(p, RUN_rRoll); }
    public static float sample_run_lYaw(float p) { return sample_run(p, RUN_lYaw); }
    public static float sample_run_lPitch(float p) { return sample_run(p, RUN_lPitch); }
    public static float sample_run_lRoll(float p) { return sample_run(p, RUN_lRoll); }
    public static float sample_run_bYaw(float p) { return sample_run(p, RUN_bYaw); }
    public static float sample_run_bPitch(float p) { return sample_run(p, RUN_bPitch); }
    public static float sample_run_bRoll(float p) { return sample_run(p, RUN_bRoll); }
    public static float sample_run_rlYaw(float p) { return sample_run(p, RUN_rlYaw); }
    public static float sample_run_rlPitch(float p) { return sample_run(p, RUN_rlPitch); }
    public static float sample_run_rlRoll(float p) { return sample_run(p, RUN_rlRoll); }
    public static float sample_run_llYaw(float p) { return sample_run(p, RUN_llYaw); }
    public static float sample_run_llPitch(float p) { return sample_run(p, RUN_llPitch); }
    public static float sample_run_llRoll(float p) { return sample_run(p, RUN_llRoll); }

    private static final float SPRINT_DUR = 14.0f;
    private static final float[] SPRINT_T = new float[]{0.000f, 0.090f, 0.180f, 0.270f, 0.360f, 0.450f, 0.550f, 0.640f, 0.730f, 0.820f, 0.910f, 1.000f};
    private static final float[] SPRINT_rYaw = new float[]{0.849975f, 0.820305f, 0.879646f, 0.876155f, 0.734784f, 0.478220f, 0.326377f, 0.335103f, 0.242601f, 0.232129f, 0.452040f, 0.849975f};
    private static final float[] SPRINT_rPitch = new float[]{-0.609120f, -0.513127f, -0.420624f, -0.422370f, -0.612611f, -0.787143f, -0.849975f, -0.884882f, -0.921534f, -0.933751f, -0.872665f, -0.609120f};
    private static final float[] SPRINT_rRoll = new float[]{-0.303687f, -0.389208f, -0.383972f, -0.268781f, -0.167552f, 0.052360f, 0.237365f, 0.488692f, 0.691150f, 0.617847f, 0.188496f, -0.303687f};
    private static final float[] SPRINT_lYaw = new float[]{0.061087f, 0.513127f, 0.581195f, 0.624828f, 0.568977f, -0.099484f, -0.783653f, -1.073377f, -1.097812f, -1.054179f, -0.752237f, 0.061087f};
    private static final float[] SPRINT_lPitch = new float[]{0.935496f, 0.607375f, 0.383972f, 0.357792f, 0.670206f, 0.912807f, 0.928515f, 0.682424f, 0.520108f, 0.654498f, 0.952950f, 0.935496f};
    private static final float[] SPRINT_lRoll = new float[]{0.418879f, 0.846485f, 1.015782f, 1.111775f, 0.921534f, 0.073304f, -0.652753f, -0.760964f, -0.520108f, -0.486947f, -0.284489f, 0.418879f};
    private static final float[] SPRINT_bYaw = new float[]{-0.132645f, 0.015708f, 0.164061f, 0.123918f, 0.054105f, 0.082030f, 0.045379f, -0.019199f, 0.080285f, 0.181514f, 0.000000f, -0.132645f};
    private static final float[] SPRINT_bPitch = new float[]{0.000000f, -0.289725f, -0.434587f, -0.462512f, -0.216421f, 0.034907f, 0.293215f, 0.523599f, 0.698132f, 0.626573f, 0.359538f, 0.000000f};
    private static final float[] SPRINT_bRoll = new float[]{0.293215f, 0.274017f, 0.188496f, 0.139626f, 0.167552f, 0.204204f, 0.242601f, 0.239110f, 0.240855f, 0.282743f, 0.286234f, 0.293215f};
    private static final float[] SPRINT_hYaw = new float[]{0.061087f, -0.010472f, -0.083776f, -0.069813f, -0.057596f, -0.047124f, 0.000000f, 0.090757f, 0.085521f, 0.048869f, 0.090757f, 0.061087f};
    private static final float[] SPRINT_hPitch = new float[]{0.012217f, 0.268781f, 0.425860f, 0.462512f, 0.301942f, 0.052360f, -0.202458f, -0.466003f, -0.610865f, -0.556760f, -0.356047f, 0.012217f};
    private static final float[] SPRINT_hRoll = new float[]{-0.342085f, -0.247837f, -0.155334f, -0.083776f, -0.083776f, -0.226893f, -0.253073f, -0.239110f, -0.162316f, -0.188496f, -0.270526f, -0.342085f};
    private static final float[] SPRINT_rlYaw = new float[]{-0.183260f, -0.010472f, 0.188496f, 0.075049f, 0.000000f, 0.052360f, -0.024435f, -0.092502f, -0.050615f, -0.055851f, -0.176278f, -0.183260f};
    private static final float[] SPRINT_rlPitch = new float[]{-0.090757f, -0.191986f, -0.258309f, -0.214675f, -0.235619f, -0.143117f, -0.106465f, 0.012217f, 0.043633f, -0.026180f, -0.078540f, -0.090757f};
    private static final float[] SPRINT_rlRoll = new float[]{0.630064f, 0.085521f, -0.108210f, -0.099484f, 0.195477f, 0.773181f, 1.178097f, 1.165880f, 0.958186f, 0.708604f, 0.753982f, 0.630064f};
    private static final float[] SPRINT_llYaw = new float[]{-0.094248f, 0.020944f, 0.094248f, 0.064577f, 0.094248f, 0.092502f, 0.012217f, -0.069813f, -0.047124f, 0.024435f, -0.055851f, -0.094248f};
    private static final float[] SPRINT_llPitch = new float[]{0.113446f, 0.013963f, -0.211185f, -0.178024f, -0.087266f, 0.000000f, 0.031416f, 0.137881f, 0.212930f, 0.267035f, 0.204204f, 0.113446f};
    private static final float[] SPRINT_llRoll = new float[]{0.930260f, 1.242674f, 1.267109f, 0.938987f, 0.774926f, 0.776672f, 0.389208f, -0.038397f, -0.134390f, 0.045379f, 0.336849f, 0.930260f};
    public static float sample_sprint(float progress, float[] data) {
        float t = MathHelper.clamp(progress, 0f, 1f);
        float[] T = SPRINT_T;
        if (t <= T[0]) return data[0];
        if (t >= T[T.length - 1]) return data[data.length - 1];
        for (int i = 0; i < T.length - 1; i++) {
            if (t <= T[i + 1]) {
                float span = T[i + 1] - T[i];
                float u = span <= 0 ? 0f : (t - T[i]) / span;
                return MathHelper.lerp(u, data[i], data[i + 1]);
            }
        }
        return data[data.length - 1];
    }
    public static float sample_sprint_rYaw(float p) { return sample_sprint(p, SPRINT_rYaw); }
    public static float sample_sprint_rPitch(float p) { return sample_sprint(p, SPRINT_rPitch); }
    public static float sample_sprint_rRoll(float p) { return sample_sprint(p, SPRINT_rRoll); }
    public static float sample_sprint_lYaw(float p) { return sample_sprint(p, SPRINT_lYaw); }
    public static float sample_sprint_lPitch(float p) { return sample_sprint(p, SPRINT_lPitch); }
    public static float sample_sprint_lRoll(float p) { return sample_sprint(p, SPRINT_lRoll); }
    public static float sample_sprint_bYaw(float p) { return sample_sprint(p, SPRINT_bYaw); }
    public static float sample_sprint_bPitch(float p) { return sample_sprint(p, SPRINT_bPitch); }
    public static float sample_sprint_bRoll(float p) { return sample_sprint(p, SPRINT_bRoll); }
    public static float sample_sprint_rlYaw(float p) { return sample_sprint(p, SPRINT_rlYaw); }
    public static float sample_sprint_rlPitch(float p) { return sample_sprint(p, SPRINT_rlPitch); }
    public static float sample_sprint_rlRoll(float p) { return sample_sprint(p, SPRINT_rlRoll); }
    public static float sample_sprint_llYaw(float p) { return sample_sprint(p, SPRINT_llYaw); }
    public static float sample_sprint_llPitch(float p) { return sample_sprint(p, SPRINT_llPitch); }
    public static float sample_sprint_llRoll(float p) { return sample_sprint(p, SPRINT_llRoll); }

    private static final float JUMP_DUR = 30.0f;
    private static final float[] JUMP_T = new float[]{0.000f, 0.090f, 0.180f, 0.270f, 0.360f, 0.450f, 0.550f, 0.640f, 0.730f, 0.820f, 0.910f, 1.000f};
    private static final float[] JUMP_rYaw = new float[]{0.827286f, 0.584685f, 0.326377f, 0.284489f, 0.308923f, -0.397935f, -0.902335f, -0.907571f, -0.781908f, -0.685914f, -0.733038f, -0.802851f};
    private static final float[] JUMP_rPitch = new float[]{-0.347321f, 0.172788f, 0.195477f, 0.204204f, 0.033161f, -0.356047f, -0.146608f, -0.092502f, -0.171042f, -0.233874f, -0.207694f, -0.198968f};
    private static final float[] JUMP_rRoll = new float[]{-0.389208f, -0.928515f, -0.816814f, -0.488692f, -0.094248f, 0.719076f, 1.155408f, 1.174607f, 1.041962f, 0.996583f, 1.024508f, 1.069887f};
    private static final float[] JUMP_lYaw = new float[]{-0.870919f, -0.568977f, -0.219911f, -0.125664f, -0.089012f, 0.712094f, 0.804597f, 0.781908f, 0.795870f, 0.769690f, 0.767945f, 0.750492f};
    private static final float[] JUMP_lPitch = new float[]{0.619592f, 0.150098f, 0.233874f, 0.226893f, 0.324631f, 0.335103f, 0.308923f, 0.185005f, 0.160570f, 0.198968f, 0.226893f, 0.240855f};
    private static final float[] JUMP_lRoll = new float[]{-0.586431f, -0.890118f, -0.666716f, -0.479966f, -0.136136f, 0.897099f, 1.087340f, 1.122247f, 1.108284f, 1.082104f, 1.069887f, 1.075123f};
    private static final float[] JUMP_bYaw = new float[]{-0.020944f, -0.036652f, -0.033161f, -0.020944f, -0.036652f, -0.048869f, -0.027925f, -0.033161f, -0.038397f, -0.033161f, -0.027925f, -0.024435f};
    private static final float[] JUMP_bPitch = new float[]{-0.017453f, -0.097738f, -0.115192f, -0.109956f, -0.069813f, 0.000000f, 0.000000f, 0.015708f, 0.022689f, 0.000000f, 0.000000f, 0.000000f};
    private static final float[] JUMP_bRoll = new float[]{0.162316f, 0.125664f, 0.150098f, 0.151844f, 0.204204f, 0.343830f, 0.291470f, 0.181514f, 0.101229f, 0.087266f, 0.075049f, 0.062832f};
    private static final float[] JUMP_hYaw = new float[]{0.026180f, 0.020944f, 0.000000f, 0.000000f, 0.000000f, 0.019199f, 0.038397f, 0.029671f, 0.020944f, 0.015708f, 0.012217f, 0.019199f};
    private static final float[] JUMP_hPitch = new float[]{0.104720f, 0.108210f, 0.116937f, 0.113446f, 0.087266f, 0.052360f, 0.078540f, 0.041888f, 0.010472f, 0.000000f, 0.000000f, 0.000000f};
    private static final float[] JUMP_hRoll = new float[]{0.082030f, 0.061087f, 0.101229f, 0.055851f, -0.061087f, -0.188496f, -0.160570f, -0.106465f, -0.089012f, -0.089012f, -0.083776f, -0.094248f};
    private static final float[] JUMP_rlYaw = new float[]{0.101229f, 0.115192f, 0.109956f, 0.102974f, 0.043633f, 0.000000f, 0.010472f, 0.024435f, 0.041888f, 0.054105f, 0.062832f, 0.069813f};
    private static final float[] JUMP_rlPitch = new float[]{-0.233874f, -0.240855f, -0.190241f, -0.191986f, -0.115192f, -0.034907f, -0.078540f, -0.115192f, -0.125664f, -0.132645f, -0.144862f, -0.157080f};
    private static final float[] JUMP_rlRoll = new float[]{0.335103f, 0.043633f, 0.062832f, 0.071558f, 0.312414f, 0.544543f, 0.366519f, 0.162316f, 0.068068f, 0.034907f, 0.013963f, 0.000000f};
    private static final float[] JUMP_llYaw = new float[]{-0.076794f, -0.087266f, -0.038397f, -0.040143f, -0.045379f, -0.094248f, -0.076794f, -0.090757f, -0.101229f, -0.089012f, -0.083776f, -0.082030f};
    private static final float[] JUMP_llPitch = new float[]{0.190241f, 0.235619f, 0.150098f, 0.167552f, 0.122173f, 0.102974f, 0.101229f, 0.160570f, 0.181514f, 0.178024f, 0.174533f, 0.172788f};
    private static final float[] JUMP_llRoll = new float[]{0.321141f, 0.057596f, 0.054105f, 0.047124f, 0.270526f, 0.532325f, 0.345575f, 0.092502f, 0.043633f, 0.019199f, 0.022689f, 0.036652f};
    public static float sample_jump(float progress, float[] data) {
        float t = MathHelper.clamp(progress, 0f, 1f);
        float[] T = JUMP_T;
        if (t <= T[0]) return data[0];
        if (t >= T[T.length - 1]) return data[data.length - 1];
        for (int i = 0; i < T.length - 1; i++) {
            if (t <= T[i + 1]) {
                float span = T[i + 1] - T[i];
                float u = span <= 0 ? 0f : (t - T[i]) / span;
                return MathHelper.lerp(u, data[i], data[i + 1]);
            }
        }
        return data[data.length - 1];
    }
    public static float sample_jump_rYaw(float p) { return sample_jump(p, JUMP_rYaw); }
    public static float sample_jump_rPitch(float p) { return sample_jump(p, JUMP_rPitch); }
    public static float sample_jump_rRoll(float p) { return sample_jump(p, JUMP_rRoll); }
    public static float sample_jump_lYaw(float p) { return sample_jump(p, JUMP_lYaw); }
    public static float sample_jump_lPitch(float p) { return sample_jump(p, JUMP_lPitch); }
    public static float sample_jump_lRoll(float p) { return sample_jump(p, JUMP_lRoll); }
    public static float sample_jump_bYaw(float p) { return sample_jump(p, JUMP_bYaw); }
    public static float sample_jump_bPitch(float p) { return sample_jump(p, JUMP_bPitch); }
    public static float sample_jump_bRoll(float p) { return sample_jump(p, JUMP_bRoll); }
    public static float sample_jump_rlYaw(float p) { return sample_jump(p, JUMP_rlYaw); }
    public static float sample_jump_rlPitch(float p) { return sample_jump(p, JUMP_rlPitch); }
    public static float sample_jump_rlRoll(float p) { return sample_jump(p, JUMP_rlRoll); }
    public static float sample_jump_llYaw(float p) { return sample_jump(p, JUMP_llYaw); }
    public static float sample_jump_llPitch(float p) { return sample_jump(p, JUMP_llPitch); }
    public static float sample_jump_llRoll(float p) { return sample_jump(p, JUMP_llRoll); }

    private static final float DASH_DUR = 16.0f;
    private static final float[] DASH_T = new float[]{0.000f, 0.090f, 0.180f, 0.270f, 0.360f, 0.450f, 0.550f, 0.640f, 0.730f, 0.820f, 0.910f, 1.000f};
    private static final float[] DASH_rYaw = new float[]{-1.445133f, -1.363102f, -1.190315f, -0.869174f, -0.659734f, -1.073377f, -1.244420f, -1.160644f, -1.223476f, -0.958186f, 0.504400f, 1.598722f};
    private static final float[] DASH_rPitch = new float[]{-0.654498f, -0.727802f, -0.822050f, -0.886627f, -0.905826f, -0.624828f, -0.205949f, 0.019199f, -0.136136f, -0.727802f, -0.673697f, -0.087266f};
    private static final float[] DASH_rRoll = new float[]{1.007055f, 0.954695f, 0.844739f, 0.638791f, 0.588176f, 1.038471f, 1.530654f, 1.633628f, 1.322960f, 0.752237f, -0.490438f, -0.930260f};
    private static final float[] DASH_lYaw = new float[]{0.678933f, 0.467748f, 0.328122f, 0.153589f, 0.280998f, 0.211185f, -0.279253f, -0.783653f, -1.195551f, -1.097812f, -0.322886f, 0.556760f};
    private static final float[] DASH_lPitch = new float[]{0.844739f, 0.853466f, 0.825541f, 0.753982f, 0.717330f, 0.619592f, 0.462512f, 0.429351f, 0.291470f, 0.450295f, 0.938987f, 0.968658f};
    private static final float[] DASH_lRoll = new float[]{0.657989f, 0.422370f, 0.230383f, 0.054105f, 0.249582f, 0.308923f, 0.078540f, -0.294961f, -0.705113f, -0.445059f, 0.132645f, 0.991347f};
    private static final float[] DASH_bYaw = new float[]{-0.015708f, 0.043633f, 0.080285f, 0.082030f, 0.075049f, 0.031416f, -0.010472f, -0.059341f, -0.176278f, -0.284489f, -0.322886f, -0.317650f};
    private static final float[] DASH_bPitch = new float[]{-0.411898f, -0.247837f, -0.076794f, -0.038397f, -0.054105f, 0.013963f, 0.113446f, 0.202458f, 0.200713f, 0.191986f, 0.073304f, -0.197222f};
    private static final float[] DASH_bRoll = new float[]{0.509636f, 0.385718f, 0.275762f, 0.204204f, 0.144862f, 0.153589f, 0.183260f, 0.211185f, 0.246091f, 0.289725f, 0.403171f, 0.584685f};
    private static final float[] DASH_hYaw = new float[]{0.068068f, 0.132645f, 0.171042f, 0.153589f, 0.059341f, -0.062832f, -0.125664f, -0.115192f, 0.000000f, 0.151844f, 0.267035f, 0.258309f};
    private static final float[] DASH_hPitch = new float[]{-0.328122f, -0.335103f, -0.350811f, -0.314159f, -0.275762f, -0.200713f, -0.141372f, -0.183260f, -0.366519f, -0.408407f, -0.212930f, 0.095993f};
    private static final float[] DASH_hRoll = new float[]{-0.191986f, -0.342085f, -0.464258f, -0.520108f, -0.406662f, -0.401426f, -0.417134f, -0.436332f, -0.467748f, -0.544543f, -0.581195f, -0.553269f};
    private static final float[] DASH_rlYaw = new float[]{0.256563f, 0.141372f, 0.144862f, 0.167552f, 0.162316f, 0.064577f, 0.000000f, -0.068068f, -0.221657f, -0.378736f, -0.345575f, -0.183260f};
    private static final float[] DASH_rlPitch = new float[]{-0.254818f, -0.310669f, -0.357792f, -0.354302f, -0.298451f, -0.230383f, -0.162316f, -0.019199f, 0.193732f, 0.137881f, 0.052360f, -0.022689f};
    private static final float[] DASH_rlRoll = new float[]{0.017453f, 0.424115f, 0.696386f, 0.649262f, 0.439823f, 0.261799f, 0.698132f, 1.466077f, 1.862266f, 1.431170f, 1.008800f, 0.439823f};
    private static final float[] DASH_llYaw = new float[]{-0.178024f, -0.120428f, 0.020944f, 0.094248f, 0.052360f, -0.031416f, -0.068068f, -0.116937f, -0.158825f, -0.104720f, -0.066323f, -0.020944f};
    private static final float[] DASH_llPitch = new float[]{0.200713f, 0.211185f, 0.176278f, 0.267035f, 0.352557f, 0.249582f, 0.139626f, 0.176278f, 0.267035f, 0.287979f, 0.261799f, 0.315905f};
    private static final float[] DASH_llRoll = new float[]{0.427606f, 0.952950f, 1.439897f, 1.480039f, 1.308997f, 0.991347f, 0.738274f, 0.427606f, 0.076794f, 0.071558f, 0.483456f, 1.246165f};
    public static float sample_dash(float progress, float[] data) {
        float t = MathHelper.clamp(progress, 0f, 1f);
        float[] T = DASH_T;
        if (t <= T[0]) return data[0];
        if (t >= T[T.length - 1]) return data[data.length - 1];
        for (int i = 0; i < T.length - 1; i++) {
            if (t <= T[i + 1]) {
                float span = T[i + 1] - T[i];
                float u = span <= 0 ? 0f : (t - T[i]) / span;
                return MathHelper.lerp(u, data[i], data[i + 1]);
            }
        }
        return data[data.length - 1];
    }
    public static float sample_dash_rYaw(float p) { return sample_dash(p, DASH_rYaw); }
    public static float sample_dash_rPitch(float p) { return sample_dash(p, DASH_rPitch); }
    public static float sample_dash_rRoll(float p) { return sample_dash(p, DASH_rRoll); }
    public static float sample_dash_lYaw(float p) { return sample_dash(p, DASH_lYaw); }
    public static float sample_dash_lPitch(float p) { return sample_dash(p, DASH_lPitch); }
    public static float sample_dash_lRoll(float p) { return sample_dash(p, DASH_lRoll); }
    public static float sample_dash_bYaw(float p) { return sample_dash(p, DASH_bYaw); }
    public static float sample_dash_bPitch(float p) { return sample_dash(p, DASH_bPitch); }
    public static float sample_dash_bRoll(float p) { return sample_dash(p, DASH_bRoll); }
    public static float sample_dash_rlYaw(float p) { return sample_dash(p, DASH_rlYaw); }
    public static float sample_dash_rlPitch(float p) { return sample_dash(p, DASH_rlPitch); }
    public static float sample_dash_rlRoll(float p) { return sample_dash(p, DASH_rlRoll); }
    public static float sample_dash_llYaw(float p) { return sample_dash(p, DASH_llYaw); }
    public static float sample_dash_llPitch(float p) { return sample_dash(p, DASH_llPitch); }
    public static float sample_dash_llRoll(float p) { return sample_dash(p, DASH_llRoll); }

    private LocomotionClips() {}
}