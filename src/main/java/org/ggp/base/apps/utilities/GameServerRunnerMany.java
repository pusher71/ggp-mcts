package org.ggp.base.apps.utilities;

import org.ggp.base.util.gdl.factory.exceptions.GdlFormatException;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.symbol.factory.exceptions.SymbolFormatException;

import java.io.IOException;

public class GameServerRunnerMany {

    public static void main(String[] args) throws IOException, SymbolFormatException, GdlFormatException, InterruptedException, GoalDefinitionException
    {
        for (int i = 0; i < Integer.parseInt(args[0]); i++)
            GameServerRunner.main(new String[]
                    {
                            "MyTur",            // Название турнира (название папки, куда поместятся результаты)
                            "TicTacToe",        // Название игры
                            "1",                // Время перед началом матча
                            "1",                // Время на совершение хода
                            "127.0.0.1",        // Данные игрока 1 (игроков нужно предварительно создать и настроить)
                            "9147",
                            "MyPlayerRandom",
                            "127.0.0.1",        // Данные игрока 2
                            "9148",
                            "MyPlayerMC"
                    });
    }
}
