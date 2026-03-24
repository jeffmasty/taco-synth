package judahzone.synth;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import judahzone.synth.Dco.DcoJson;
import judahzone.synth.Synth.SynthType;
import judahzone.util.JsonUtil;
import judahzone.util.RTLogger;
import lombok.Getter;

public class SynthDB {

	static List<SynthJson> programs = new ArrayList<>();

	@Getter static String[] patches;

	/** @return number of definitions loaded */
	public static int init(File file) {
		ObjectMapper mapper = JsonUtil.MAPPER;
		// load synths from json file
		try {
			SynthJson[] raw = mapper.readValue(file, SynthJson[].class);
			programs = new ArrayList<>(raw.length);
			for (SynthJson s : raw) {
				// Jackson deserializes Object fields as ArrayList<LinkedHashMap>.
				// Re-map custom to the concrete type for each SynthType at load time,
				// so accept() receives the correct object rather than raw maps.
				SynthJson fixed = s;
				if (s.type() == SynthType.Wav && !(s.custom() instanceof DcoJson[])) {
					DcoJson[] oscs = mapper.convertValue(s.custom(),
							mapper.getTypeFactory().constructArrayType(DcoJson.class));
					fixed = new SynthJson(s.name(), s.kernal(), s.type(), oscs);
				}
				programs.add(fixed);
			}
			patches = new String[programs.size()];
			for (int i = 0; i < programs.size(); i++)
				patches[i] = programs.get(i).name();
		} catch (Exception e) {
			programs.clear();
			patches = new String[0];
			e.printStackTrace();
		}
		return programs.size();
	}


	public static SynthJson get(int index) {
		return programs.get(index);
	}

	public static SynthJson get(String preset) {
		 for (SynthJson json : programs)
			 if (json.name().equals(preset))
				 return json;
		 return null;
	}

	public static int getIndex(String name) {
		 for (int i = 0; i < programs.size(); i++)
			 if (programs.get(i).name().equals(name))
				 return i;
		 return -1;
	}

	public static void save(File file, Synth update) {
		try {
			int replace = update.getProgChange();
			programs.set(replace, update.get());
			SynthJson[]	raw = programs.toArray(new SynthJson[0]);
			JsonUtil.writeJson(raw, file);
			RTLogger.log(SynthDB.class, "Saved " + update.getProgram());
		} catch (Exception e) {
			RTLogger.warn(SynthDB.class, "Failed to save synth definitions: " + e.getMessage());
		}
	}

}
