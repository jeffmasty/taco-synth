package judahzone.synth;

import javax.sound.midi.ShortMessage;

import judahzone.fx.MonoFilter;
import judahzone.util.Constants;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**crank up Resonance on Filters
 * caller handles gui updates */
@RequiredArgsConstructor
public class ModWheel {

	public static final int ZERO_BEND = 8192;
	private static final int RESONANCE = MonoFilter.Settings.Resonance.ordinal();

	@Getter @Setter private int semitones = 2;

	private final MonoFilter lo;
	private final MonoFilter hi;

	private Integer oldHi;
	private Integer oldLo;

	void dragged(int data2) {

		if (data2 == 0) {
			if (oldLo != null)
				lo.set(RESONANCE, oldLo);
			if (oldHi != null)
				hi.set(RESONANCE, oldHi);
			oldHi = null;
			oldLo = null;
			return;
		}

		if (oldHi == null) {
			oldHi = hi.get(RESONANCE);
			oldLo = lo.get(RESONANCE);
		}

		float ratio = data2 * Constants.TO_1;
		int newLo = oldLo + (int) (100 * ratio);
		newLo = oldLo + Math.round((100 - oldLo) * ratio);
		if (newLo > 100)
			newLo = 100;
		lo.set(RESONANCE, newLo);
		int newHi = oldHi + (int) (100 * ratio);
		if (newHi > 100)
			newHi = 100;
		hi.set(RESONANCE, newHi);
	}

	public float bendFactor(ShortMessage m) {
		return bendFactor(m, semitones);
	}

	/**Pitch Bend message mapping:
	 * 1. Combine MSB and LSB (msb << 7 | lsb)
	 * 2. Center is ZERO_BEND (8192). Normalize to -1..+1 by (value - center)/center
	 * 3. Multiply by semitone range and convert to factor via 2^(semitones/12)
	 */
	public static float bendFactor(ShortMessage pitchMsg, int semitones) {
		if (pitchMsg == null) return 1f;
		// According to MIDI/ShortMessage semantics: data1 = LSB, data2 = MSB
		int lsb = pitchMsg.getData1() & 0x7F;
		int msb = pitchMsg.getData2() & 0x7F;
		int raw14 = (msb << 7) | lsb; // 0..16383

		// Center is ZERO_BEND (8192). Map to -1..+1
		float norm = (raw14 - ZERO_BEND) / (float) ZERO_BEND; // approx -1..+1 (max slightly < 1)
		// Clamp just in case
		if (norm < -1f) norm = -1f;
		if (norm > 1f) norm = 1f;

		// semitone offset (signed)
		float semitoneOffset = norm * semitones;
		// convert semitone offset to frequency factor
		return (float) Math.pow(2.0, semitoneOffset / 12.0);
	}


}
