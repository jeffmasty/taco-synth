// file: judahzone/synth/Voice.java
package judahzone.synth;

import java.util.function.Consumer;

import judahzone.data.Letter;
import judahzone.prism.Envelope;
import judahzone.prism.Envelope.Delta;
import judahzone.synth.Dco.DcoJson;
import judahzone.util.AudioTools;
import judahzone.util.Constants;
import judahzone.util.Frequency;
import judahzone.util.Ramp;

public class Voice implements Consumer<DcoJson[]> {

    protected final Envelope env;
    protected volatile Dco[] oscillators = new Dco[0]; // atomic swap

    protected volatile int data1 = 0;  // RT-read: MIDI note

    private final int n_samples;

    // smoothing for velocity changes (modwheel, aftertouch, data2?) // TODO implemented?
    protected final Ramp velocityRamp;

    /** Default constructor uses a default Envelope. */
    public Voice() {
        this(new Letter(1, 100, 0f, 0, Synth.SR));
    }

    /** Construct VOX with a shared envelope configuration (each VOX gets its own Envelope instance). */
    public Voice(Letter letter) {
        env = new Envelope(letter);
        int factor = letter.sr() / Constants.sampleRate(); // oversampling factor (e.g. 4)
        n_samples = Constants.bufSize() * factor; // oversampled buffer size for this voice
        velocityRamp = new Ramp(n_samples);
    }

    public void reset(int data1, int data2) {
        this.data1 = data1;
        float velocity = data2 * Constants.TO_1;
        float hz = Frequency.midiToHz(data1);
        for (Dco osc : oscillators)
        	osc.reset(hz, velocity);
        env.trigger();
    }

    /** Called from MIDI thread to trigger release/clean state for this voice. */
    public void noteOff() {
        // Start the release stage in the envelope.
        // The envelope will drive the release and transition to complete,
        // at which point process() will clear data1.
        env.release();
    }

    public Dco[] getAlgo() {
    	return oscillators.clone();
    }

    /** Update the envelope configuration for this voice (RT-safe config change). */
    public void sendLetter(Letter l) {
        if (l == null) return;
        env.sendLetter(l);
    }

    /**@param zeroed work buffer
     * @param output mix into */
    public void process(float[] scratch, float[] output) {
        if (data1 <= 0) return;

        // work from fresh
        AudioTools.silence(scratch);

        // generate voice into scratch
        Dco[] local = oscillators; // local ref for RT safety
        for (int i = 0; i < local.length; i++)
            local[i].process(scratch);

        // oversampled envelope
        if (env.process(scratch, n_samples) < n_samples)
        	// envelope says this note is done, caller checks data1 post-process (release->gui)
        	data1 = 0;

        AudioTools.mix(scratch, output);
    }

    public void bend(float factor) {
        Dco[] local = oscillators;
        if (local == null) return;
        for (Dco osc : local)
            osc.setBend(factor);
    }

    // TODO cross-fade algos
    @Override public void accept(DcoJson[] t) {
    	// RTLogger.log(caller, msg);
        // Build array atomically before publishing to avoid audio-thread iterating a partial array
        Dco[] newOsc = new Dco[t.length];
        for (int i = 0; i < t.length; i++)
            newOsc[i] = new Dco(t[i]);
        oscillators = newOsc;
    }

    public Delta getStage() { // for test
    	return env.getStage();
    }

    public DcoJson get(int idx) {
        Dco[] local = oscillators;
        if (idx >= 0 && idx < local.length) {
            return local[idx].get();
        }
        return null;
    }
}
