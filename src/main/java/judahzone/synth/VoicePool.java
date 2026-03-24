package judahzone.synth;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small synchronized pool mapping MIDI key -> VOX instance index. Preallocated,
 * allocation-free on audio path. Designed to be used from MIDI thread.
 *
 * New: maintains a volatile VOX[] activeSnapshot that is rebuilt on
 * allocate/release so the audio thread can iterate active voices lock-free and
 * allocation-free.
 */
public class VoicePool {

	private final Voice[] pool;
	private final AtomicReference<int[]> keyToIndexRef = new AtomicReference<>(new int[128]);
	// 0==none ; stored index+1
	private final AtomicInteger next = new AtomicInteger(0);

	/* Volatile snapshot for RT audio thread (no locks, no allocations on read). */
	private volatile Voice[] activeSnapshot = new Voice[0];

	public VoicePool(Voice[] pool) {
		this.pool = pool;
		rebuildSnapshotFrom(keyToIndexRef.get());
	}

	/* Allocate (or return existing) VOX for midiKey. Lock-free; safe on MIDI/RT threads.*/
	public Voice allocate(int midiKey) {
		if (midiKey < 0 || midiKey > 127)
			return null;
		final int poolLen = pool.length;

		while (true) {
			int[] old = keyToIndexRef.get();
			int mapped = old[midiKey];
			if (mapped != 0)
				return pool[mapped - 1];

			int[] upd = Arrays.copyOf(old, old.length);

			// pick a free slot starting from next (heuristic)
			int start = Math.floorMod(next.getAndIncrement(), poolLen);
			int candidate = -1;
			outer: for (int i = 0; i < poolLen; i++) {
				int idx = (start + i) % poolLen;
				// check if idx is in use in the current snapshot (old)
				for (int v : old) {
					if (v == idx + 1)
						continue outer;
				}
				candidate = idx;
				break;
			}
			if (candidate == -1) {
				// no free slot found -> steal `start`
				candidate = start % poolLen;
				// clear any existing mapping to candidate in the working copy
				for (int k = 0; k < upd.length; k++)
					if (upd[k] == candidate + 1)
						upd[k] = 0;
			}

			upd[midiKey] = candidate + 1;

			// try to publish the new mapping
			if (keyToIndexRef.compareAndSet(old, upd)) {
				// update next heuristic and publish active snapshot derived from upd
				next.set((candidate + 1) % poolLen);
				rebuildSnapshotFrom(upd);
				return pool[candidate];
			}
			// CAS failed -> retry (optimistic concurrency)
		}
	}

	/** Release mapping for midiKey. Lock-free. */
	public void release(int midiKey) {
		if (midiKey <= 0)
			return;

		int[] old = keyToIndexRef.get();
		int mapped = old[midiKey];
		if (mapped == 0)
			return; // already released
		int[] upd = Arrays.copyOf(old, old.length);
		upd[midiKey] = 0;
		if (keyToIndexRef.compareAndSet(old, upd))
			rebuildSnapshotFrom(upd);
	}

	/** Lookup VOX for midiKey (null if none). Lock-free snapshot read. */
	public Voice lookup(int midiKey) {
		if (midiKey < 0 || midiKey > 127)
			return null;
		int[] map = keyToIndexRef.get();
		int mapped = map[midiKey];
		return mapped == 0 ? null : pool[mapped - 1];
	}

	/**
	 * Return a volatile snapshot of currently allocated VOX instances. RT-safe:
	 * caller must not modify the returned array.
	 */
	public Voice[] getActiveSnapshot() {
		return activeSnapshot;
	}

	/**
	 * Rebuild the snapshot array from provided keyToIndex map. Called after a
	 * successful publish; allocates here only (on MIDI path).
	 */
	private void rebuildSnapshotFrom(int[] keyToIndex) {
		// gather used indices
		int count = 0;
		for (int v : keyToIndex)
			if (v != 0)
				count++;
		if (count == 0) {
			activeSnapshot = new Voice[0];
			return;
		}
		Voice[] snap = new Voice[count];
		int pos = 0;
		boolean[] used = new boolean[pool.length];
		for (int k = 0; k < keyToIndex.length; k++) {
			int tag = keyToIndex[k];
			if (tag != 0) {
				int idx = tag - 1;
				if (!used[idx]) {
					snap[pos++] = pool[idx];
					used[idx] = true;
				}
			}
		}
		if (pos != snap.length) {
			Voice[] trimmed = Arrays.copyOf(snap, pos);
			activeSnapshot = trimmed;
		} else {
			activeSnapshot = snap;
		}
	}
}
