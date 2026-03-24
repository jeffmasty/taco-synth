// file: judahzone/synth/Dco.java
package judahzone.synth;

import java.util.function.Consumer;
import java.util.function.Supplier;

import judahzone.data.Letter;
import judahzone.data.Shape;
import judahzone.util.Constants;
import judahzone.util.Interpolation;
import judahzone.util.Phase;
import judahzone.util.Ramp;

/** Digital Controlled Oscillator with pitch control, detune, phase accumulation and crossfade triggering.*/
public class Dco implements Consumer<Dco.DcoJson>, Supplier<Dco.DcoJson> { // TODO pan (gain fx per dco)

	public static record DcoJson(Shape shape, float level, int coarse, float fine)
	{	public DcoJson(Shape shape) {
			this(shape, 0.6f, 0, 0f);
		}}

    private static final int BUF_SIZE = Constants.bufSize() * Synth.OVERSAMPLE;
	private static final int SAMPLE_RATE = Constants.sampleRate() * Synth.OVERSAMPLE;
    private static final int blendSamples = Math.max(1, (int) (SAMPLE_RATE * Letter.SMOOTH_MS / 1000f));
    private static final float invSR = 1f / SAMPLE_RATE;
    private static final Interpolation Cubic = Interpolation.CUBIC;

    private final Ramp pitchRamp = new Ramp(blendSamples);
    private final Phase phase = new Phase(blendSamples); // crossfade to zero over blendSamples

    private float freq; // from data1
    private int coarse = 0;
    private float fine = 0f;
    private float bend = 1f; // ratio_decimal
    private float level = 1f; // mix between oscillators
    private float velocity; // from data2
    private float mix;

    private Shape shape = Shape.SIN;

    public Dco(DcoJson dcoJson) {
    	accept(dcoJson);
	}

	public void reset(float hz, float amp) { // TODO also receive DCOJson (won't have mutate all DCOs on update, just on trigger?)
        freq = hz;
        velocity = amp;
        mix = level * velocity;
        if (freq > 0f)
            phase.trigger();

        // smooth the effective ratio (detune * bend) rather than detune alone
        pitchRamp.setTarget(getDetuneRatio());
    }

    /** detune is an ratio_decimal (absolute multiplier). */
    public void setDetune(int coarse, float fine) {
        this.coarse = coarse;
        this.fine = fine;
        // non-RT setter -> start smoothing toward new effective ratio
        pitchRamp.setTarget(getDetuneRatio());
    }

    public void setBend(float amount) {
        bend = amount;
        // smooth the effective ratio including bend
        pitchRamp.setTarget(getDetuneRatio());
    }

    /** @param env current envelope setting
     * @param output proccessAdd */
    public void process(float[] output) {

        final int mask = Shape.MASK;
        final int len = Shape.LENGTH;
        final int buf = BUF_SIZE;

        final float[] wave = shape.getWave();
        Ramp localR = pitchRamp; // local ref for RT safety
        Phase localP = phase;

        // Base increment (cycles/sample without detune)
        final float basePhaseFactor = Math.max(freq * bend * invSR, 0f);
        final float lvl = mix;

        // hoist first sample
        float currentDetune = localR.next();
        float cyclesPerSample = currentDetune * basePhaseFactor;
        float blendedPhase = localP.next(cyclesPerSample);
        float idxf = blendedPhase * len;
        int index = ((int) idxf) & mask;
        float frac = idxf - (int) idxf;

        float s_m1 = wave[(index - 1) & mask];
        float s0 = wave[index];
        float s1 = wave[(index + 1) & mask];
        float s2 = wave[(index + 2) & mask];
        float sample = Cubic.interp(s_m1, s0, s1, s2, frac);
        output[0] += lvl * sample;


        for (int i = 1; i < buf; i++) {
            currentDetune = localR.next();
            cyclesPerSample = currentDetune * basePhaseFactor;
            blendedPhase = localP.next(cyclesPerSample);
            idxf = blendedPhase * len;
            index = ((int) idxf) & mask;
            frac = idxf - (int) idxf;

            s_m1 = wave[(index - 1) & mask];
            s0 = wave[index];
            s1 = wave[(index + 1) & mask];
            s2 = wave[(index + 2) & mask];
            sample = Cubic.interp(s_m1, s0, s1, s2, frac);

            output[i] += lvl * sample;
        }
    }

	@Override
	public DcoJson get() {
		return new DcoJson(shape, level, coarse, fine);
	}

	@Override
	public void accept(DcoJson t) {
		shape = t.shape();
		level = t.level();
		setDetune(t.coarse(), t.fine());
		mix = level * velocity;
	}

	public void setShape(Shape shape) {
		this.shape = shape;
	}

	public void setLevel(float level) {
		this.level = level;
		this.mix = level * velocity;
	}

	private float getDetuneRatio() {
		return (float) Math.pow(2, (coarse + fine) / 12.0);
	}
}
