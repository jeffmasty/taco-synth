package judahzone.synth;

import java.security.InvalidParameterException;
import java.util.List;

import judahzone.api.Custom;
import judahzone.api.MidiUpdate;
import judahzone.data.Letter;
import judahzone.data.Pair;
import judahzone.data.Shape;
import judahzone.fx.op.Kernel;
import judahzone.synth.Dco.DcoJson;
import judahzone.util.RTLogger;

public class WavSynth extends Synth {

	private final float[] scratch = new float[N_FRAMES * OVERSAMPLE]; // for individual voice envelopes

	public static final Custom GENERIC = new Custom(
			WavSynth.class.getSimpleName(), false, true, // mono,show
		null, null, null, // jack ports
		"taco.png", 45, 10_000,
		SynthType.Wav.name(), false, 0.1f
	);

	public static final DcoJson[] ALGO = {
			new DcoJson(Shape.SIN),
			new DcoJson(Shape.SQR),
	};

	public static final int MAX_DCO = 8;

	private final Voice[] poly_voice = new Voice[POLYPHONY];
	private final VoicePool voicePool;
	/** current preset or user edit */
	private DcoJson[] algo = ALGO;

	public WavSynth(SynthJson json) {
		this(json, new MidiUpdate.Mock());
	}

	public WavSynth(SynthJson json, MidiUpdate gui) {
	    super(gui);
	    Letter shared = getLetter();
	    for (int i = 0; i < POLYPHONY; i++) {
	        poly_voice[i] = new Voice(shared);
	        // initialize voice oscillators with default algo so notes produce sound immediately
	        poly_voice[i].accept(algo);
	    }
	    // preallocate the pool with the VOX instances
	    voicePool = new VoicePool(poly_voice);
	    accept(json);
	}

    @Override public void processImpl() {
        Voice[] active = voicePool.getActiveSnapshot();
        for (Voice v : active) {
        	int note = v.data1;

        	if (note <= 0) // skip "zombie" voices that have finished (data1==0)
        		continue;

        	v.process(scratch, work);

        	if (v.data1 <= 0)
                // Voice just finished during the process call.
                voicePool.release(note);
        }

    }


    /*
@Override public void processImpl() {
    Voice[] active = voicePool.getActiveSnapshot();
    for (Voice v : active) {
        int currentKey = v.data1;
        if (currentKey <= 0) {
            // Mapping exists in pool but voice is idle.
            // This happens if we don't call release() at the exact sample the env ends.
            continue;
        }

        AudioTools.silence(scratch);
        v.process(scratch, work);

        if (v.data1 <= 0) {
            // Voice just finished during this process call.
            voicePool.markFinished(currentKey);
        }
    }
}     */

	@Override public void bend(float factor) {
        for (Voice v : poly_voice) // all or just active voices?
            v.bend(factor);
	}

	@Override protected void onMidiNoteOn(int data1, int data2) {
	    // MIDI thread: allocate a VOX and initialize it
	    Voice v = voicePool.allocate(data1);
	    if (v != null)
	        v.reset(data1, data2); // triggers envelope and primes oscillators
	}

	@Override protected void onMidiNoteOff(int data1) {
	    // MIDI thread: notify VOX of note-off to start release
	    Voice v = voicePool.lookup(data1);
	    if (v != null)
	        v.noteOff();

	    // Note: We do NOT call voicePool.release(data1) here.
	    // Doing so would remove the voice from activeSnapshot immediately, cutting off the release tail.
	    // Instead, we let the voice finish naturally; processImpl() ignores it once idle,
	    // and VoicePool will steal the slot when needed.
	}

	@Override public void accept(SynthJson t) {
        assert t.type() == SynthType.Wav;
        if (t.custom() instanceof DcoJson[] oscs) {
            assert !t.name().isBlank();
            prog = new Pair(t.name(), SynthDB.getIndex(t.name()));
            setAlgo(oscs);
        } else {
            RTLogger.warn(WavSynth.class, "custom is not DcoJson[]: "
                    + (t.custom() == null ? "null" : t.custom().getClass()));
        }
        super.accept(t);
	}

	// store and propagate to all voices
	public void setAlgo(DcoJson[] algo) {
		this.algo = algo;
		// propagate changes to running voices (UI-thread safe)
		for (Voice v : poly_voice)
			if (v != null)
				v.accept(algo);
		broadcast();
	}

	/** Broadcast a new envelope configuration to all voices (each voice keeps independent state). */
	@Override public void sendEnvelope(Letter l) {
		if (l == null) return;
		for (Voice v : poly_voice)
			if (v != null)
				v.sendLetter(l);
	}

	@Override public SynthJson get() {
		 return new SynthJson(prog.key(), kernal(), SynthType.Wav, algo);
	}

	public DcoJson[] getAlgo() {
	    // algo is the canonical state; voice DCOs are derived from it.
	    // Reading back from voice[0] risks stale/mismatched counts (e.g., after progChange
	    // before any note is triggered, or if Voice.accept() resizes differently).
	    DcoJson[] snapshot = new DcoJson[algo.length];
	    for (int i = 0; i < algo.length; i++)
	        snapshot[i] = algo[i];
	    return snapshot;
	}

	@Override public int[] actives() 				{ return notes.actives(); }
	public Voice getVoice(int idx)					{ return poly_voice[idx]; } // for test

	@Override public void close() 					{ panic();}
	@Override public String[] getPatches() 			{ return SynthDB.getPatches(); }
	@Override public int getProgChange() 			{ return prog == null ? 0 : (int) prog.value(); }
	@Override public String getProgram() 			{ return prog == null ? "" : prog.key(); }
	@Override public String progChange(int data2) 	{ accept(SynthDB.get(data2)); return prog.key(); }
	@Override public int progChange(String preset) 	{ accept(SynthDB.get(preset)); return (int) prog.value(); }

	@Override
	public List<String> getParams() {
		return Kernel.LABELS_LIST;
		// TODO dco count, per dco shape level detune
	}

	@Override
	public void set(int idx, int knob) {
		if (idx < Kernel.PARAM_COUNT)
			kernel(idx, knob);
		else {
			int param = (idx - 1) % 10;
			int dco = idx / 10;
			if (dco >= algo.length)
				throw new InvalidParameterException("DCO index " + dco + " out of range");
			switch (param) {
				case 0 -> setShape(dco, Shape.values()[knob]);
				case 1 -> setLevel(dco, knob); // level 0..100
				case 2 -> setCoarse(dco, knob - 12); // coarse -12..12
				case 3 -> setFine(dco, knob); // fine 0..100
				default -> throw new InvalidParameterException("param " + param);
			}
		}
		broadcast();
	}

	@Override
	public int get(int idx) {
		if (idx < Kernel.PARAM_COUNT)
			return kernel(idx);
		int param = (idx - 1) % 10;
		int dco = idx / 10;
		if (dco >= algo.length)
			throw new InvalidParameterException("DCO index " + dco + " out of range");
		DcoJson json = algo[dco];
		return switch (param) {
			case 0 -> json.shape().ordinal();
			case 1 -> (int) (json.level() * 100);
			case 2 -> json.coarse() + 12;
			case 3 -> (int) (json.fine() * 50 + 50);
			default -> throw new InvalidParameterException("param " + param);
		};
	}



	// return -0.99 to 0.99 in 0 to 100 (slider)
	public int getFine(int idx) {
		DcoJson json = algo[idx];
		return (int) (json.fine() * 50 + 50);
	}

	public int getCoarse(int idx) {
		DcoJson json = algo[idx];
		return json.coarse();
	}

	public void setCoarse(int idx, int semi) {
		DcoJson json = algo[idx];
		algo[idx] = new DcoJson(json.shape(), json.level(), semi, json.fine());
		for (Voice v : poly_voice) {
			if (v != null) v.oscillators[idx].setDetune(semi, json.fine());
		}
		broadcast();
	}

	/** @param cents -1..+1 */
	public void setFine(int idx, int knob) {
		float fine = (knob - 50) * 0.02f;
		DcoJson json = algo[idx];
		algo[idx] = new DcoJson(json.shape(), json.level(), json.coarse(), fine);
		for (Voice v : poly_voice) {
			if (v != null) v.oscillators[idx].setDetune(json.coarse(), fine);
		}
		broadcast();
	}

	public void setShape(int idx, Shape shape) {
		DcoJson json = algo[idx];
		algo[idx] = new DcoJson(shape, json.level(), json.coarse(), json.fine());
		for (Voice v : poly_voice) {
			if (v != null) v.oscillators[idx].setShape(shape);
		}
		broadcast();
	}
	public void setLevel(int idx, int level) {
		DcoJson json = algo[idx];
		float newLevel = level * 0.01f;
		algo[idx] = new DcoJson(json.shape(), newLevel, json.coarse(), json.fine());
		for (Voice v : poly_voice) {
			if (v != null) v.oscillators[idx].setLevel(newLevel);
		}
		broadcast();
	}


	public void setModSemitones(int value) {
		modWheel.setSemitones(value);
		broadcast();
	}


}
