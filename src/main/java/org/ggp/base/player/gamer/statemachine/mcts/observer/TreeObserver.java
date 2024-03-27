package org.ggp.base.player.gamer.statemachine.mcts.observer;

import com.google.gson.*;
import org.ggp.base.player.gamer.statemachine.mcts.event.TreeEvent;
import org.ggp.base.player.gamer.statemachine.mcts.event.TreeStartEvent;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.observer.Event;
import org.ggp.base.util.observer.Observer;
import org.ggp.base.util.statemachine.MachineState;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;

public class TreeObserver implements Observer {

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(MachineState.class, new MachineStateSerializer())
            .create();
    private String folder;

    @Override
    public void observe(Event event) {
        try {
            if (event instanceof TreeStartEvent) {

                // Создать папку с деревьями
                folder = "Trees_" + System.currentTimeMillis();
                File f = new File(folder);
                f.mkdir();

            } else if (event instanceof TreeEvent) {

                TreeEvent treeEvent = ((TreeEvent) event);

                // Записать дерево в файл
                File f = new File(folder + "/Tree_" + treeEvent.getTurnNumber() + ".json");
                f.createNewFile();
                BufferedWriter bw = new BufferedWriter(new FileWriter(f));
                bw.write(gson.toJson(treeEvent.getTree()));
                bw.flush();
                bw.close();

            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static class MachineStateSerializer implements JsonSerializer<MachineState> {
        @Override
        public JsonElement serialize(MachineState src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray result = new JsonArray();
            for (GdlSentence sentence : src.getContents()) {
                result.add(sentence.toString());
            }
            return result;
        }
    }
}
