# taco-synth
Hand-crafted Synthesizers for the JudahZone project  

## Highlights

- Realtime-friendly polyphonic digital oscillator subtractive synthesizer.
- MIDI: javax Receiver `send(MidiMessage, long)` entry point
- Supports NoteOn Velocity, Pitch Bend, ModWheel, ProgChange and CC Pedal handling
- Add/remove up to 8 waveshapes (Dco) per voice, with adjustable mix and detune.
- Supports pass filters, gain and stereo panning
- Generates 4x over-sampled in mono before decimating into stereo

## Key classes / files

- `Synth` — base for synth engines 
- `WavSynth` — Synth instance based on subtractive wave table LUT
- `Voice` — per-note Dco container: hz, velocity, allocation and envelope state.
- `Dco` — digital-controlled oscillator using `Shape` wave tables, mix and detune.
- `ModWheel` — maps pitch-bend controller commands.
- `SynthDB` / `SynthJson` — preset serialization and loader (json).

#### Core/FX dependencies:  
- `MidiOut\MidiPiano` - external interface
- `Shape` — pre-computed wave tables
- `Envelope` ADSR from core/prism 
- `Notes` — UI-facing active notes + voice slot management.
- helpers: Gain, MonoFilter, Phase, Ramp, AudioTools, AudioMetrics

## Design notes

- MIDI handling:
  - Receiver's `send()` queues RT-safe commands to `Notes` ring buffers and triggers UI updates via `MidiUpdate`.
  - Pitch bend implemented via `bendFactor(...)` helper (14-bit mapping per MIDI spec).
- Extensibility:
	`Synth` provides the base class for future FM/Pluck-style oscillators
- Realtime constraints:
  - No `new` or boxed primitives in the audio callback path.
  - Use/reuse fixed-size `float[]`.
  - Parameter changes smoothed (ramp) to avoid zipper noise.
  - Heavy work (Math.sin(), preset I/O, parsing, UI updates) is off-RT.
- Unit Tests (zone-test project): SynthTest, ShapeTest, EnvelopeTest, RampTest

## Future

Accent, Glissando, Glide, Pressure, Breath, Envelope to filter, PWM, Mono-Synth  
LFOs: modwheel, filter, dco level, dco detune, pwm  

