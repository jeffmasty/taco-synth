package judahzone.synth;

import java.security.InvalidParameterException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.ShortMessage;

import judahzone.api.ControlChange;
import judahzone.api.Midi;
import judahzone.api.MidiOut.MidiPiano;
import judahzone.api.MidiUpdate;
import judahzone.data.Letter;
import judahzone.data.Pair;
import judahzone.data.Postage;
import judahzone.fx.Gain;
import judahzone.fx.MonoFilter;
import judahzone.fx.MonoFilter.Type;
import judahzone.fx.op.Kernel;
import judahzone.fx.op.Kernel.KernelParams;
import judahzone.fx.op.Zone;
import judahzone.util.AudioTools;
import judahzone.util.Constants;
import judahzone.util.Notes;
import judahzone.util.RTLogger;
import judahzone.util.Threads;
import lombok.Getter;

/** Abstract base for synth engines (Taco, FM, Pluck).
 * Provides common controls and RT-friendly helpers. */
public abstract class Synth implements Zone, MidiPiano, KernelParams, Consumer<SynthJson>, Supplier<SynthJson> {
	public static enum SynthType { Wav }// , Pluck, FM, Fluid } "Bass" "303"

	public static final int OVERSAMPLE = 4; // anti-aliasing
	/** generation sample rate (oversampled) */
	public static final int SR = Constants.sampleRate() * OVERSAMPLE;
	public static final int POLYPHONY = 32; // test purposes
	protected static final int N_FRAMES = Constants.bufSize();
	protected static final float TWO_PI = (float)(2.0 * Math.PI);

	protected final MidiUpdate gui;
	@Getter protected final Notes notes;

	/** synchronize Voices against this envelope setup */
    @Getter private volatile Letter letter = new Letter(10, 100, 0.75f, 100, Constants.sampleRate() * OVERSAMPLE);
	private final MonoFilter aliasFilter = new MonoFilter(Type.HiCut, 16500, OVERSAMPLE);

	@Getter protected final Gain gain = new Gain();
    @Getter protected final MonoFilter lowCut = new MonoFilter(Type.LoCut, 40, 0.1f, 1);
	@Getter protected final MonoFilter hiCut = new MonoFilter(Type.HiCut, 9000, 0.1f, 1);
    @Getter protected final ModWheel modWheel = new ModWheel(lowCut, hiCut);

	protected final float[] work = new float[N_FRAMES * OVERSAMPLE]; // generate mono
	protected final float[] left = new float[N_FRAMES];
	protected final float[] right = new float[N_FRAMES];

	protected volatile boolean dirty = true;

	protected Pair prog = new Pair("", 0);

    protected Synth(MidiUpdate ping) {
    	gui = ping;
    	notes = new Notes(this, gui, Synth.POLYPHONY);
	}

	protected void broadcast() {
		gui.updateSynth(this);
	}

    @Override
	public void accept(SynthJson json) {
    	if (json == null || json.kernal() == null)
			return;
    	Kernel k = json.kernal();
    	gain.set(k.gain());
		lowCut.setFrequency(k.low().hz());
		lowCut.setResonance(k.low().reso());
		hiCut.setFrequency(k.hi().hz());
		hiCut.setResonance(k.hi().reso());
		setEnv(new Letter(k.env(), Constants.sampleRate() * OVERSAMPLE));
		broadcast();
    }

    /** json */
    public Kernel kernal() {
    	return new Kernel(gain, letter, lowCut, hiCut);
	}

    /** get */
    	public int kernel(int index) {
    	return switch (index) {
    		case PREAMP -> (int)(gain.getPreamp() * 100);
    		case GAIN -> (int)(gain.getGain() * 100);
    		case PAN -> (int)(gain.getPan() * 100);
    		case ATK -> letter.attackPct();
    		case DK -> letter.decayPct();
    		case SUS -> letter.sustainPct();
    		case REL -> letter.releasePct();
    		case LOHZ -> lowCut.get(MonoFilter.Settings.Frequency.ordinal());
    		case LORES -> (lowCut.get(MonoFilter.Settings.Resonance.ordinal()));
    		case HIHZ -> hiCut.get(MonoFilter.Settings.Frequency.ordinal());
    		case HIRES -> (hiCut.get(MonoFilter.Settings.Resonance.ordinal()));

    		default -> throw new InvalidParameterException("idx " + index);
    	};
    }

    /** set */
    protected void kernel(int index, int knob) {
    	switch(index) {
    		case PREAMP -> gain.setPreamp(knob * 0.01f);
			case GAIN -> gain.setGain(knob * 0.01f);
			case PAN -> gain.setPan(knob * 0.01f);
			case ATK -> setEnv(letter.withAttackPct(knob));
			case DK -> setEnv(letter.withDecayPct(knob));
			case SUS -> setEnv(letter.withSustain(knob * 0.01f));
			case REL -> setEnv(letter.withReleasePct(knob));
			case LOHZ -> lowCut.set(MonoFilter.Settings.Frequency.ordinal(), knob);
			case LORES -> {
				lowCut.set(MonoFilter.Settings.Resonance.ordinal(), knob);

				RTLogger.log(this, knob + " loRes to " + lowCut.getResonance());


			}
			case HIHZ -> hiCut.set(MonoFilter.Settings.Frequency.ordinal(), knob);
			case HIRES -> hiCut.set(MonoFilter.Settings.Resonance.ordinal(), knob);

			default -> throw new InvalidParameterException("idx " + index);
    	}
    }

    @Override
	public Postage getPostage() {
    	return letter.getAdsr();
    }

    @Override public final void setPedal(boolean hold) {
    	notes.setPedal(hold);
	}

    @Override public boolean isPedal() {
		return notes.isPedal();
	}

	/////////////////////////////////
	//     PROCESS AUDIO           //
	/////////////////////////////////
	///
	///	  1. voice DCOs upsampled
	///   2. anti-alias filter
	///   3. decimate to mono
	///	  4. preset filters
	public final void process(float[] outLeft, float[] outRight) {

		AudioTools.silence(work);
        processImpl();

        aliasFilter.process(work);
		AudioTools.decimate(work, work, OVERSAMPLE);
        hiCut.process(work);
        lowCut.process(work);

        // TODO IR convolution on mono

		// 5. Spatialization
		gain.monoToStereo(work, outLeft, outRight);

	}

    /** Per-buffer audio processing (real-time). active voices generate(). */
    public abstract void processImpl();

    public void setEnv(Letter l) {
		this.letter = l;
		sendEnvelope(l);
		broadcast();
	}
    protected abstract void sendEnvelope(Letter spec);

    public abstract void bend(float factor);
    protected abstract void onMidiNoteOn(int data1, int velocity);
    protected abstract void onMidiNoteOff(int data1);

    @Override public void on(ShortMessage midi) {
    	int note = midi.getData1();
    	int velocity = midi.getData2();
    	onMidiNoteOn(note, velocity);
    	notes.on(note, velocity); // RT-safe: queue into ring, then flush UI-side update immediately
    }

    @Override public void off(int data1) {
    	onMidiNoteOff(data1);
    	notes.off(data1);
	}

	@Override
	public final void send(MidiMessage midi, long timeStamp) {
	    if (midi instanceof MetaMessage) return;
	    ShortMessage m = (ShortMessage)midi;
	    if (Midi.isProgChange(m)) {
	    	progChange(m.getData1());
	    	return;
	    }
	    if (filterPiano(m))
	    	return;
	    // TODO	if (cc.process(m)) return;
	    if (ccEnv(m))
	    	return;

	    if (Midi.isNoteOn(m)) // TODO velocity scalar  (Sched.amp)
	    	on(m);
	    else if (Midi.isNoteOff(m))
	    	off(m.getData1());
	    else if (ControlChange.MODWHEEL.matches(m))
	        modWheel.dragged(m.getData2());
	    else if (Midi.isPitchBend(m))
	        bend(modWheel.bendFactor(m));
	    else
	        RTLogger.debug(this, "skip " + Midi.toString(m));
	}

	private boolean ccEnv(ShortMessage msg) {
		if (!Midi.isCC(msg))
			return false;
		// Envelope
		ControlChange type = ControlChange.find(msg.getData1());
		if (type == null)
			return false;
		int val = (int) (msg.getData2() * Constants.TO_100);

		switch(type) {
		// TODO fine-tune
			case ATTACK:
				setEnv(letter.withAttackPct(val));
				break;
			case DECAY:
				setEnv(letter.withDecayPct(val));
				break;
			case SUSTAIN:
				setEnv(letter.withSustainPct(val));
				break;
			case RELEASE:
				setEnv(letter.withReleasePct(val));
				break;
//			case DETUNE:
//				setDetune(val);
//				break;
//			case GLIDE: // wowser
//				break;
//				//case PORTAMENTO: ????
			default:
				return false;

		}
		return true;
	}

//	/**Convert knob/CC input to floating point on DCO-0
//	 * @param val 0 to 100 based around 50*/
//	public void setDetune(int val) {
//		detune[0] = (val - 50f) * 0.001f + 1f;
//		for (VOX voice : voices)
//			voice.detune();
//	}


	protected boolean filterPiano(MidiMessage m) {
	    if (m instanceof ShortMessage midi) {
	        if (ControlChange.PEDAL.matches(midi)) {
	            notes.setPedal(midi.getData2() > Midi.CUTOFF);
	            return true;
	        }
	        if (ControlChange.PANIC.matches(midi)) {
	            notes.setPedal(false);
	            Threads.execute(() -> panic()); // no loops on Panic(), the class
	            return true;
	        }
	    }
	    return false;
	}

	public static Synth factory(SynthJson synthJson, MidiUpdate gui) {
		if (synthJson.type() == SynthType.Wav)
			return new WavSynth(synthJson, gui);
		else
			throw new IllegalArgumentException("Unknown synth type: " + synthJson.type());


	}

}
