package mc;

/**
 * Ve kterém režimu hra právě je. Řídí, co se kreslí, co poslouchá vstup
 * a jestli je vidět kurzor.
 */
public enum GameState {

    /** Hlavní menu po spuštění. */
    MAIN_MENU,

    /** Generuje se a mešuje svět, běží loading screen s postupem. */
    CREATING_WORLD,

    /** Normální hraní, kurzor je chycený. */
    PLAYING,

    /** Pauza vyvolaná Escapem - svět se pořád kreslí, ale nehýbe se. */
    PAUSED,

    /** Otevřený inventář nebo crafting table. Svět stojí, kurzor je volný. */
    CONTAINER,

    /** Texture lab - úpravy dlaždic atlasu (F6 nebo z hlavního menu). */
    TEXTURE_LAB,

    /** Nastavení - z hlavního menu i z pauzy (pak se za ním kreslí svět). */
    OPTIONS,

    /** Seznam uložených světů s náhledy. */
    SELECT_WORLD,

    /** Jméno a seed nového světa. */
    CREATE_WORLD
}
