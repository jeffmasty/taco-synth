package judahzone.synth;

import judahzone.fx.op.Kernel;
import judahzone.synth.Synth.SynthType;

/*common:  envelope, GainT, Shelf
  specific:
	wav: DCO[] algo
	pluck: pluckParams
	FM: algo, ops[]     	*/
public record SynthJson( // (not fluid)
		String name,
		Kernel kernal,
		SynthType type, // wav/pluck/fm/taco/
		Object custom
		) {

}
