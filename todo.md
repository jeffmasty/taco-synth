Overview
--------
WavSynth is a lightweight synth module used by JudahZone for MIDI-driven synth engines.
New:  WavSynth in new project.  (re-usable api)
Legacy: TacoSynth (deleted)

-----------
Next Steps:
-----------

PianoKeys paint notes
STACK Status: ?  
add a compact, thread-safe pre-allocated stack and note->voice mapping to WavSynth. 
Purpose: avoid allocations after ctor: grab a pre-allocated VOX for each new noteOn, config, then process()
	each VOX can be re-used after released,  
capacity: Synth.POLYPHONY (e.g., 24 for TacoSynth, 8 for DrumSet)
type: VOX   ::new pre-allocate
MIDI NOTE_ON grabs the next available VOX
	Shouldn't have a round-robin stealing algo because:
	Notes checks polyphony (it can be configured for a mono-synth) and/or:
	Synth.POLYPHONY is visible and should be checked (ignored) prior to any note grab?
onVoiceGrab(VOX voice) {
	voice.accept(algo); // don't forget to prime pre-allocated/stale VOX 
NOTE_OFF releases the VOX back onto the free stack. (<-- GUI-side bug) 
All stack ops are synchronized (MIDI/non-RT path); audio path remains lock-free.
Unit tests: (adapt/extend NotesTest.java) 
	Sequence some notes, 
	load test POLYPHONY


### Channels graph  
next: channels vs. tracks vs. engines/singles vs. Luthier vs. Songs 
redo songs, sched, bundle, trackInfo
Upgrade User Channels (AddTrack.refillSynth createSynth)
Maybe Notes should only attach/observe if visible?  Or maybe they should always attach but only update if visible?


### Smoothing:  centralize on `judahzone.util.Ramp`.
// In `judahzone.synth.taco.Dco`:
+ private final judahzone.util.Ramp pitchRamp;         // smooth cyclesPerSample / frequency changes (setHz, setBend, detune)

// In `judahzone.synth.taco.Voice`:
x envelope handles Ramp ampRamp;           // smooth totalGain / velocity/ENVELOPE transitions for click-free level changes

// Optional / future (glide/portamento):
private final judahzone.util.Ramp glidePitchRamp;    // for future portamento / glide per-voice

// In `judahzone.synth.taco.TacoSynth`:
private final judahzone.util.Ramp[] gainRamp;        // per-DCO smoothing for dcoGain[] (size DCO_COUNT)
private final judahzone.util.Ramp[] detuneRamp;      // per-DCO smoothing for detune[] (size DCO_COUNT)

private final judahzone.util.Ramp masterDampenRamp;  // smooth global dampen changes (used by TacoShell.getDampen()/setter) purpose?

// In deprecated `judahzone.synth.taco.TacoShell`: // purpose?
x private final judahzone.util.Ramp dampenRamp;        // smooth dampen parameter before applying in calcEnv

// In `judahzone.synth.taco.ModWheel`:
x Ramp resLoRamp;         // smooth low-filter resonance changes handled by FX unit
x Ramp resHiRamp;         // smooth high-filter resonance changes handled by FX unit


